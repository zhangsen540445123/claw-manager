package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class OpenClawUserDataCleaner {
  private static final Pattern AGENT_ID = Pattern.compile("user_[0-9a-f]{32}");

  private final InstanceFileService fileService;
  private final ObjectMapper objectMapper;

  public OpenClawUserDataCleaner(InstanceFileService fileService, ObjectMapper objectMapper) {
    this.fileService = fileService;
    this.objectMapper = objectMapper;
  }


  public List<String> readOldSessionIds(String instanceId, String oldAgentId) {
    validateAgentId(oldAgentId);
    InstancePaths paths = fileService.paths(instanceId);
    Path stateRoot = paths.homeDir().resolve(".openclaw").toAbsolutePath().normalize();
    Path sessionsIndex = checkedChild(
        stateRoot,
        stateRoot.resolve("agents").resolve(oldAgentId).resolve("sessions").resolve("sessions.json")
    );
    if (!Files.exists(sessionsIndex)) {
      return List.of();
    }
    try {
      JsonNode root = objectMapper.readTree(sessionsIndex.toFile());
      Set<String> sessionIds = new java.util.LinkedHashSet<>();
      collectSessionFields(root, "", sessionIds);
      return List.copyOf(sessionIds);
    } catch (IOException error) {
      throw new IllegalStateException("读取旧 Agent 会话索引失败。", error);
    }
  }

  public void deleteOldUserData(
      String instanceId,
      String oldAgentId,
      List<String> oldSessionIds,
      List<String> deletedApiPeers
  ) {
    validateAgentId(oldAgentId);
    InstancePaths paths = fileService.paths(instanceId);
    Path stateRoot = paths.homeDir().resolve(".openclaw").toAbsolutePath().normalize();
    deleteTree(checkedChild(stateRoot, stateRoot.resolve("agents").resolve(oldAgentId)));
    deleteTree(checkedChild(stateRoot, stateRoot.resolve("workspace-" + oldAgentId)));

    Set<String> exactMatches = new HashSet<>();
    exactMatches.add(oldAgentId);
    addNormalized(exactMatches, oldSessionIds);
    addApiPeerMatches(exactMatches, deletedApiPeers);
    rewriteEntries(stateRoot.resolve("openviking").resolve("active-turns.json"), exactMatches);
    rewriteEntries(stateRoot.resolve("openviking").resolve("sender-handoff.json"), exactMatches);
  }


  private void collectSessionFields(JsonNode node, String fieldName, Set<String> sessionIds) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isTextual() && fieldName.toLowerCase(java.util.Locale.ROOT).contains("session")) {
      String value = node.asText().trim();
      if (!value.isBlank()) {
        sessionIds.add(value);
      }
      return;
    }
    if (node.isObject()) {
      node.fields().forEachRemaining(entry -> collectSessionFields(entry.getValue(), entry.getKey(), sessionIds));
    } else if (node.isArray()) {
      node.forEach(child -> collectSessionFields(child, fieldName, sessionIds));
    }
  }

  private void validateAgentId(String agentId) {
    if (agentId == null || !AGENT_ID.matcher(agentId.trim()).matches()) {
      throw new IllegalArgumentException("旧 Agent ID 格式无效。");
    }
  }

  private void rewriteEntries(Path path, Set<String> exactMatches) {
    if (!Files.exists(path)) {
      return;
    }
    try {
      JsonNode root = objectMapper.readTree(path.toFile());
      if (!(root instanceof ObjectNode rootObject) || !(rootObject.path("entries") instanceof ObjectNode entries)) {
        return;
      }
      List<String> remove = new ArrayList<>();
      Iterator<String> names = entries.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (exactMatches.contains(name) || containsExact(entries.get(name), exactMatches)) {
          remove.add(name);
        }
      }
      if (remove.isEmpty()) {
        return;
      }
      remove.forEach(entries::remove);
      atomicWrite(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(rootObject));
    } catch (IOException error) {
      throw new IllegalStateException("清理 OpenViking 本地状态失败。", error);
    }
  }

  private boolean containsExact(JsonNode node, Set<String> exactMatches) {
    if (node == null || node.isNull()) {
      return false;
    }
    if (node.isTextual()) {
      return exactMatches.contains(node.asText());
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) {
        if (containsExact(child, exactMatches)) {
          return true;
        }
      }
    }
    return false;
  }

  private void atomicWrite(Path target, byte[] content) throws IOException {
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
    try {
      Files.write(temp, content);
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private Path checkedChild(Path root, Path candidate) {
    Path normalized = candidate.toAbsolutePath().normalize();
    if (!normalized.startsWith(root) || normalized.equals(root)) {
      throw new IllegalArgumentException("拒绝删除实例目录之外的路径。");
    }
    return normalized;
  }

  private void deleteTree(Path target) {
    if (!Files.exists(target)) {
      return;
    }
    try (var paths = Files.walk(target)) {
      List<Path> ordered = paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList();
      for (Path path : ordered) {
        Files.deleteIfExists(path);
      }
    } catch (IOException error) {
      throw new IllegalStateException("删除旧 Agent 本地文件失败。", error);
    }
  }

  private static void addApiPeerMatches(Set<String> target, List<String> values) {
    for (String value : values == null ? List.<String>of() : values) {
      String normalized = value == null ? "" : value.trim();
      if (normalized.isBlank()) {
        continue;
      }
      target.add(normalized);
      if (normalized.startsWith("api:") && normalized.length() > "api:".length()) {
        target.add(normalized.substring("api:".length()));
      }
    }
  }

  private static void addNormalized(Set<String> target, List<String> values) {
    for (String value : values == null ? List.<String>of() : values) {
      if (value != null && !value.isBlank()) {
        target.add(value.trim());
      }
    }
  }
}
