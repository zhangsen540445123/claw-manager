package com.clawbotforall.heartbeat;

import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.wechat.WechatLogSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * 对 OpenClaw 本地 Session 做只读 Heartbeat 污染扫描。
 *
 * <p>扫描器只相信运行元数据和专用 Session Key，不根据消息正文中的数字或
 * HEARTBEAT_OK 猜测，避免误伤普通用户会话。</p>
 */
@Service
public class HeartbeatSessionScanner {
  public static final long MAX_SESSION_INDEX_BYTES = 4L * 1024 * 1024;
  public static final long MAX_ACTIVE_STATE_BYTES = 2L * 1024 * 1024;
  public static final long MAX_TRANSCRIPT_BYTES = 2L * 1024 * 1024;
  public static final int MAX_AGENTS = 256;
  public static final int MAX_SESSIONS = 10_000;
  public static final int MAX_TRANSCRIPT_LINES = 200;

  private static final Set<String> HEARTBEAT_BOOLEAN_FIELDS = Set.of(
      "isheartbeat", "heartbeat", "heartbeatrun");
  private static final Set<String> HEARTBEAT_KIND_FIELDS = Set.of(
      "trigger", "runkind", "runtype");
  private static final Set<String> HEARTBEAT_CONTEXT_FIELDS = Set.of(
      "runtime", "context", "run");
  private final InstanceFileService fileService;
  private final ObjectMapper objectMapper;
  private final OpenVikingSettingsService openVikingSettingsService;

  public HeartbeatSessionScanner(
      InstanceFileService fileService,
      ObjectMapper objectMapper,
      OpenVikingSettingsService openVikingSettingsService
  ) {
    this.fileService = fileService;
    this.objectMapper = objectMapper;
    this.openVikingSettingsService = openVikingSettingsService;
  }

  public ScanReport scanInstance(String instanceId) {
    return scanInstanceDetailed(instanceId).toPublicReport();
  }

  DetailedScanReport scanInstanceDetailed(String instanceId) {
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("实例 ID 不能为空。");
    }
    InstancePaths paths = fileService.paths(instanceId);
    Path stateRoot = paths.homeDir().resolve(".openclaw").normalize();
    Path agentsRoot = stateRoot.resolve("agents");
    LinkedHashSet<String> warnings = new LinkedHashSet<>();
    String identitySecret = openVikingSettingsService.effectiveSettings().identityHashSecret();
    ProtectedSessionReferences protectedReferences = readProtectedSessionReferences(
        stateRoot, identitySecret, warnings);
    List<DetailedSessionCandidate> candidates = new ArrayList<>();

    if (!Files.isDirectory(agentsRoot)) {
      return new DetailedScanReport(hash(instanceId), List.of(), List.of("agents_directory_missing"));
    }

    List<Path> agentDirs = listDirectories(agentsRoot, MAX_AGENTS, warnings, "agent_limit_reached");
    for (Path agentDir : agentDirs) {
      scanAgent(agentDir, protectedReferences, candidates, warnings);
    }
    return new DetailedScanReport(hash(instanceId), candidates, List.copyOf(warnings));
  }

  private void scanAgent(
      Path agentDir,
      ProtectedSessionReferences protectedReferences,
      List<DetailedSessionCandidate> candidates,
      LinkedHashSet<String> warnings
  ) {
    Path sessionsDir = agentDir.resolve("sessions").normalize();
    Path indexPath = sessionsDir.resolve("sessions.json");
    if (!Files.isRegularFile(indexPath)) {
      return;
    }
    JsonNode root = readBoundedJson(indexPath, MAX_SESSION_INDEX_BYTES, warnings,
        "session_index_too_large", "session_index_invalid");
    if (root == null) {
      return;
    }
    JsonNode entries = root.has("sessions") && root.path("sessions").isObject()
        ? root.path("sessions") : root;
    if (!entries.isObject()) {
      warnings.add("session_index_invalid");
      return;
    }

    int scanned = 0;
    Iterator<Map.Entry<String, JsonNode>> iterator = entries.fields();
    while (iterator.hasNext()) {
      if (scanned++ >= MAX_SESSIONS) {
        warnings.add("session_limit_reached");
        break;
      }
      Map.Entry<String, JsonNode> entry = iterator.next();
      String sessionKey = entry.getKey();
      if (sessionKey == null || sessionKey.isBlank()) {
        warnings.add("session_key_missing");
        continue;
      }
      candidates.add(classify(agentDir.getFileName().toString(), sessionsDir,
          sessionKey, entry.getValue(), protectedReferences));
    }
  }

  private DetailedSessionCandidate classify(
      String agentId,
      Path sessionsDir,
      String sessionKey,
      JsonNode entry,
      ProtectedSessionReferences protectedReferences
  ) {
    LinkedHashSet<String> evidence = new LinkedHashSet<>();
    String canonicalSessionKey = normalizedSessionKey(sessionKey);
    boolean suffixHint = canonicalSessionKey.matches(".*(:heartbeat)+$");
    if (suffixHint) {
      evidence.add("session_key_suffix");
    }
    String isolatedBaseSessionKey = firstText(entry, "heartbeatIsolatedBaseSessionKey");
    boolean dedicated = isVerifiedIsolatedSessionKey(
        agentId, canonicalSessionKey, isolatedBaseSessionKey, evidence);
    if (!dedicated && isolatedBaseSessionKey != null) {
      evidence.add("heartbeat_isolated_base_session_key_mismatch");
    }

    boolean explicitMarker = hasHeartbeatMarker(entry);
    if (explicitMarker) {
      evidence.add("session_metadata_heartbeat_marker");
    }

    TranscriptEvidence transcript = readTranscriptEvidence(sessionsDir, entry);
    if (transcript.heartbeatMarker()) {
      explicitMarker = true;
      evidence.add("transcript_heartbeat_marker");
    }
    if (transcript.truncated()) {
      evidence.add("transcript_scan_truncated");
    }

    Classification classification;
    if (dedicated) {
      classification = Classification.HEARTBEAT_ONLY;
    } else if (explicitMarker && transcript.hasConversationContent()) {
      classification = Classification.MIXED_PRIMARY;
    } else if (explicitMarker || suffixHint || transcript.truncated()) {
      // 只有普通主 Session 的 Heartbeat 痕迹而没有可验证的隔离 Key 时，
      // 不能自动 reset；交给管理员人工确认或让用户发送 /new。
      classification = Classification.UNKNOWN;
    } else {
      classification = Classification.NORMAL;
    }

    String activeTurnKey = hmac32(protectedReferences.identitySecret(), sessionKey);
    if (activeTurnKey != null && protectedReferences.activeTurnEntryKeys().stream()
        .anyMatch(value -> value.equals(activeTurnKey) || value.startsWith(activeTurnKey + ":"))) {
      evidence.add("active_turn_reference");
      classification = Classification.ACTIVE_PROTECTED;
    } else if (protectedReferences.activeStatePresent()
        && protectedReferences.identitySecret().isBlank()
        && classification != Classification.NORMAL) {
      evidence.add("active_turn_protection_unavailable");
      classification = Classification.UNKNOWN;
    }

    SessionFinding finding = new SessionFinding(
        hash(agentId), hash(sessionKey), classification, List.copyOf(evidence));
    return new DetailedSessionCandidate(
        agentId, sessionKey, finding, transcript.hasConversationContent());
  }

  private TranscriptEvidence readTranscriptEvidence(Path sessionsDir, JsonNode entry) {
    Path transcript = resolveTranscriptPath(sessionsDir, entry);
    if (transcript == null || !Files.isRegularFile(transcript)) {
      return TranscriptEvidence.NONE;
    }
    boolean truncated;
    try {
      truncated = Files.size(transcript) > MAX_TRANSCRIPT_BYTES;
    } catch (IOException error) {
      return new TranscriptEvidence(false, true, false);
    }

    int lines = 0;
    long bytesRead = 0;
    boolean hasConversationContent = false;
    try (BufferedReader reader = Files.newBufferedReader(transcript, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines++;
        bytesRead += line.getBytes(StandardCharsets.UTF_8).length + 1L;
        if (lines > MAX_TRANSCRIPT_LINES || bytesRead > MAX_TRANSCRIPT_BYTES) {
          truncated = true;
          break;
        }
        try {
          JsonNode parsed = objectMapper.readTree(line);
          hasConversationContent = hasConversationContent || hasConversationContent(parsed);
          if (hasHeartbeatMarker(parsed)) {
            return new TranscriptEvidence(true, truncated, hasConversationContent);
          }
        } catch (IOException ignored) {
          // 单行损坏不能导致整个实例扫描失败；也不能据此自动清理。
        }
      }
    } catch (IOException error) {
      return new TranscriptEvidence(false, true, false);
    }
    return new TranscriptEvidence(false, truncated, hasConversationContent);
  }

  private Path resolveTranscriptPath(Path sessionsDir, JsonNode entry) {
    String candidate = firstText(entry, "sessionFile", "transcriptPath", "path", "file");
    if (candidate == null) {
      String sessionId = firstText(entry, "sessionId", "session_id", "id");
      if (sessionId == null || sessionId.isBlank()) {
        return null;
      }
      candidate = sessionId + ".jsonl";
    }
    Path resolved = sessionsDir.resolve(candidate).normalize();
    return resolved.startsWith(sessionsDir.normalize()) ? resolved : null;
  }

  private ProtectedSessionReferences readProtectedSessionReferences(
      Path stateRoot,
      String identitySecret,
      LinkedHashSet<String> warnings
  ) {
    Path activeTurnsPath = stateRoot.resolve("openviking").resolve("active-turns.json");
    boolean activeStatePresent = Files.isRegularFile(activeTurnsPath);
    Set<String> activeTurnEntryKeys = readActiveTurnEntryKeys(activeTurnsPath, warnings);
    String normalizedSecret = identitySecret == null ? "" : identitySecret.trim();
    if (activeStatePresent && normalizedSecret.isBlank()) {
      warnings.add("active_turn_identity_secret_missing");
    }
    return new ProtectedSessionReferences(activeTurnEntryKeys, normalizedSecret, activeStatePresent);
  }

  private Set<String> readActiveTurnEntryKeys(
      Path path,
      LinkedHashSet<String> warnings
  ) {
    if (!Files.isRegularFile(path)) {
      return Set.of();
    }
    JsonNode root = readBoundedJson(path, MAX_ACTIVE_STATE_BYTES, warnings,
        "active_turns_too_large", "active_turns_invalid");
    if (root == null) {
      return Set.of();
    }
    JsonNode entries = root.path("entries");
    if (!entries.isObject()) {
      warnings.add("active_turns_invalid");
      return Set.of();
    }
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    entries.fields().forEachRemaining(entry -> {
      JsonNode value = entry.getValue();
      if (!value.isObject() || !value.has("status") || "active".equalsIgnoreCase(value.path("status").asText())) {
        String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
        if (!key.isBlank()) {
          keys.add(key);
        }
      }
    });
    return Set.copyOf(keys);
  }

  private boolean hasConversationContent(JsonNode node) {
    if (node == null || !node.isObject()) {
      return false;
    }
    if ("message".equalsIgnoreCase(node.path("type").asText())) {
      return true;
    }
    return node.path("role").isTextual()
        && (node.has("content") || node.has("text") || node.has("message"));
  }

  private boolean hasHeartbeatMarker(JsonNode node) {
    if (node == null) {
      return false;
    }
    if (node.isObject()) {
      if (hasDirectHeartbeatMarker(node)) {
        return true;
      }
      // 仅检查 OpenClaw 运行时明确使用的上下文容器，避免递归扫描普通
      // message/metadata 业务对象造成误判。
      for (String fieldName : HEARTBEAT_CONTEXT_FIELDS) {
        JsonNode context = node.get(fieldName);
        if (context != null && hasDirectHeartbeatMarker(context)) {
          return true;
        }
      }
      return false;
    }
    if (node.isArray()) {
      for (JsonNode child : node) {
        if (hasHeartbeatMarker(child)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasDirectHeartbeatMarker(JsonNode node) {
    if (node == null || !node.isObject()) {
      return false;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String name = normalizeField(field.getKey());
      JsonNode value = field.getValue();
      if (HEARTBEAT_BOOLEAN_FIELDS.contains(name) && value.isBoolean() && value.booleanValue()) {
        return true;
      }
      if (HEARTBEAT_KIND_FIELDS.contains(name) && value.isTextual()
          && "heartbeat".equals(value.asText().trim().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private JsonNode readBoundedJson(
      Path path,
      long maxBytes,
      LinkedHashSet<String> warnings,
      String tooLargeWarning,
      String invalidWarning
  ) {
    try {
      if (Files.size(path) > maxBytes) {
        warnings.add(tooLargeWarning);
        return null;
      }
      return objectMapper.readTree(path.toFile());
    } catch (IOException error) {
      warnings.add(invalidWarning);
      return null;
    }
  }

  private static List<Path> listDirectories(
      Path root,
      int limit,
      LinkedHashSet<String> warnings,
      String limitWarning
  ) {
    try (var stream = Files.list(root)) {
      List<Path> all = stream.filter(Files::isDirectory)
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .limit((long) limit + 1)
          .toList();
      if (all.size() > limit) {
        warnings.add(limitWarning);
        return all.subList(0, limit);
      }
      return all;
    } catch (IOException error) {
      warnings.add("agents_directory_unreadable");
      return List.of();
    }
  }

  private static String firstText(JsonNode node, String... names) {
    if (node == null || !node.isObject()) {
      return null;
    }
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value != null && value.isTextual() && !value.asText().isBlank()) {
        return value.asText().trim();
      }
    }
    return null;
  }

  private static String normalizedSessionKey(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isVerifiedIsolatedSessionKey(
      String agentId,
      String canonicalSessionKey,
      String isolatedBaseSessionKey,
      LinkedHashSet<String> evidence
  ) {
    String canonicalBaseSessionKey = normalizedSessionKey(isolatedBaseSessionKey);
    if (canonicalBaseSessionKey.isBlank() || canonicalSessionKey.isBlank()) {
      return false;
    }
    String suffix = canonicalSessionKey.startsWith(canonicalBaseSessionKey)
        ? canonicalSessionKey.substring(canonicalBaseSessionKey.length()) : "";
    if (!suffix.matches("(:heartbeat)+$")) {
      return false;
    }
    String sessionAgentId = sessionAgentId(canonicalBaseSessionKey);
    if (sessionAgentId == null || !sessionAgentId.equals(normalizedSessionKey(agentId))) {
      return false;
    }
    evidence.add("heartbeat_isolated_base_session_key");
    evidence.add("heartbeat_agent_id_match");
    return true;
  }

  private static String sessionAgentId(String sessionKey) {
    String[] segments = normalizedSessionKey(sessionKey).split(":", -1);
    if (segments.length < 2 || !"agent".equals(segments[0]) || segments[1].isBlank()) {
      return null;
    }
    return segments[1];
  }

  private static String normalizeField(String value) {
    return value == null ? "" : value.replace("_", "").replace("-", "")
        .toLowerCase(Locale.ROOT);
  }

  private static String hmac32(String secret, String value) {
    String normalizedSecret = secret == null ? "" : secret.trim();
    String normalizedValue = value == null ? "" : value.trim();
    if (normalizedSecret.isBlank() || normalizedValue.isBlank()) {
      return null;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(normalizedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 32);
    } catch (Exception error) {
      throw new IllegalStateException("OpenViking active turn HMAC 计算失败。", error);
    }
  }

  private static String hash(String value) {
    return WechatLogSanitizer.identityHashPreview(value);
  }

  public enum Classification {
    HEARTBEAT_ONLY,
    MIXED_PRIMARY,
    NORMAL,
    UNKNOWN,
    ACTIVE_PROTECTED
  }

  public record SessionFinding(
      String agentIdHash,
      String sessionKeyHash,
      Classification classification,
      List<String> evidence
  ) {
    public SessionFinding {
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }

  public record ScanReport(
      String instanceIdHash,
      List<SessionFinding> findings,
      List<String> warnings
  ) {
    public ScanReport {
      findings = findings == null ? List.of() : List.copyOf(findings);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }


  static final class DetailedSessionCandidate {
    private final String agentId;
    private final String sessionKey;
    private final SessionFinding finding;
    private final boolean hasConversationContent;

    DetailedSessionCandidate(
        String agentId,
        String sessionKey,
        SessionFinding finding,
        boolean hasConversationContent
    ) {
      this.agentId = agentId;
      this.sessionKey = sessionKey;
      this.finding = finding;
      this.hasConversationContent = hasConversationContent;
    }

    String agentId() { return agentId; }
    String sessionKey() { return sessionKey; }
    SessionFinding finding() { return finding; }
    boolean hasConversationContent() { return hasConversationContent; }

    @Override
    public String toString() {
      return "DetailedSessionCandidate[finding=" + finding
          + ", hasConversationContent=" + hasConversationContent + "]";
    }
  }

  static record DetailedScanReport(
      String instanceIdHash,
      List<DetailedSessionCandidate> candidates,
      List<String> warnings
  ) {
    DetailedScanReport {
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    ScanReport toPublicReport() {
      return new ScanReport(
          instanceIdHash,
          candidates.stream().map(DetailedSessionCandidate::finding).toList(),
          warnings);
    }

    @Override
    public String toString() {
      return "DetailedScanReport[instanceIdHash=" + instanceIdHash
          + ", candidates=" + candidates + ", warnings=" + warnings + "]";
    }
  }

  private record ProtectedSessionReferences(
      Set<String> activeTurnEntryKeys,
      String identitySecret,
      boolean activeStatePresent
  ) {}

  private record TranscriptEvidence(
      boolean heartbeatMarker,
      boolean truncated,
      boolean hasConversationContent
  ) {
    private static final TranscriptEvidence NONE = new TranscriptEvidence(false, false, false);
  }
}
