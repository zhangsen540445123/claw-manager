package com.clawbotforall.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ModelCallAuditService {
  private static final Logger log = LoggerFactory.getLogger(ModelCallAuditService.class);
  private static final List<String> EVENT_TYPES = List.of("llm_input", "model_call_started", "model_call_ended", "llm_output");
  private final ModelCallAuditMapper mapper;
  private final ObjectMapper json;
  private final boolean enabled;
  private final int retentionDays;

  @Autowired
  public ModelCallAuditService(ModelCallAuditMapper mapper, ObjectMapper json,
      @Value("${clawbot.model-call-audit.enabled:true}") boolean enabled,
      @Value("${clawbot.model-call-audit.retention-days:30}") int retentionDays) {
    this.mapper = mapper;
    this.json = json;
    this.enabled = enabled;
    this.retentionDays = Math.max(1, Math.min(retentionDays, 3650));
  }

  public void record(ModelCallAuditEventRequest request) {
    if (!enabled || request == null) return;
    String eventType = value(request.eventType());
    if (!EVENT_TYPES.contains(eventType)) {
      log.warn("model-call audit ignored invalid event type: {}", eventType);
      return;
    }
    try {
      Instant created = parseInstant(request.createdAt());
      mapper.insert(new ModelCallAudit(
          "modelaudit_" + java.util.UUID.randomUUID().toString().replace("-", ""),
          eventType,
          clip(request.instanceId(), 64), clip(request.agentId(), 128), clip(request.sessionId(), 128),
          hash(value(request.sessionKey())), clip(request.runId(), 128), clip(request.callId(), 128),
          clip(request.provider(), 120), clip(request.model(), 200), clip(apiTransport(request), 120),
          clip(request.pluginVersion(), 80), value(request.systemPrompt()),
          value(request.prompt()), write(request.historyMessages()), request.imagesCount() == null ? 0 : Math.max(0, request.imagesCount()),
          writeText(request.output()), write(request.usage()), clip(request.stopReason(), 120), request.durationMs(),
          clip(request.outcome(), 40), clip(request.errorCategory(), 120), created.toString(),
          created.plus(retentionDays, ChronoUnit.DAYS).toString()));
    } catch (Exception error) {
      log.warn("model-call audit persistence failed; model execution is not affected: {}", error.toString());
    }
  }

  public List<ModelCallAudit> forTrace(String instanceId, String sessionKeyHash, String from, String to) {
    String normalizedSessionHash = value(sessionKeyHash);
    String normalizedFrom = shiftInstant(from, -30);
    String normalizedTo = shiftInstant(to, 30);
    List<ModelCallAudit> rows = mapper.findForTrace(value(instanceId), normalizedSessionHash, normalizedFrom, normalizedTo);
    if (rows.isEmpty() && !normalizedSessionHash.isBlank()) {
      rows = mapper.findForTrace(value(instanceId), "", normalizedFrom, normalizedTo);
    }
    return rows;
  }

  @Scheduled(fixedDelay = 3_600_000)
  public void cleanup() {
    try {
      mapper.deleteBefore(Instant.now().toString());
    } catch (Exception error) {
      log.warn("model-call audit cleanup failed: {}", error.toString());
    }
  }

  private String apiTransport(ModelCallAuditEventRequest request) {
    String explicit = value(request.apiTransport());
    if (!explicit.isBlank()) return explicit;
    String api = value(request.api());
    String transport = value(request.transport());
    if (!api.isBlank() && !transport.isBlank() && !api.equalsIgnoreCase(transport)) return api + "/" + transport;
    if (!api.isBlank()) return api;
    return transport;
  }

  private String write(Object value) {
    if (value == null) return "null";
    try { return json.writeValueAsString(value); }
    catch (Exception error) { return "null"; }
  }

  private String writeText(Object value) {
    if (value == null) return "";
    if (value instanceof String text) return text;
    return write(value);
  }

  private Instant parseInstant(String value) {
    try { return value(value).isBlank() ? Instant.now() : Instant.parse(value(value)); }
    catch (RuntimeException ignored) { return Instant.now(); }
  }

  private String shiftInstant(String value, long seconds) {
    try {
      return Instant.parse(value(value)).plus(seconds, ChronoUnit.SECONDS).toString();
    } catch (RuntimeException ignored) {
      return value(value);
    }
  }

  private String hash(String value) {
    if (value.isBlank()) return "";
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  private String clip(String value, int max) {
    String normalized = value(value);
    return normalized.length() <= max ? normalized : normalized.substring(0, max);
  }

  private String value(String value) { return value == null ? "" : value.trim(); }
}
