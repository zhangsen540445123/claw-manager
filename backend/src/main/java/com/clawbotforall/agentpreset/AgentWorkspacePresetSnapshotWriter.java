package com.clawbotforall.agentpreset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AgentWorkspacePresetSnapshotWriter {
  private static final String SNAPSHOT_NAME = "workspace-preset.json";
  private final Path dataDir;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  public AgentWorkspacePresetSnapshotWriter(com.clawbotforall.config.ClawbotProperties properties) {
    this(Path.of(properties.paths().dataDir()));
  }

  public AgentWorkspacePresetSnapshotWriter(Path dataDir) {
    this.dataDir = dataDir;
  }

  public void writeForInstance(String instanceId, AgentWorkspacePreset preset) {
    Path home = dataDir.resolve("instances").resolve(instanceId).resolve("home");
    try {
      Files.createDirectories(home.resolve(".openclaw").resolve("claw-manager"));
      Path target = home.resolve(".openclaw").resolve("claw-manager").resolve(SNAPSHOT_NAME);
      Path temporary = target.resolveSibling(SNAPSHOT_NAME + ".tmp");
      objectMapper.writeValue(temporary.toFile(), toMap(preset));
      try {
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException error) {
      throw new IllegalStateException("写入 Agent 工作区预设快照失败。", error);
    }
  }

  Map<String, Object> readSnapshot(String instanceId) throws IOException {
    Path target = dataDir.resolve("instances").resolve(instanceId).resolve("home")
        .resolve(".openclaw").resolve("claw-manager").resolve(SNAPSHOT_NAME);
    return objectMapper.readValue(target.toFile(), new TypeReference<>() {});
  }

  private static Map<String, Object> toMap(AgentWorkspacePreset preset) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", preset.version());
    result.put("agentsMd", preset.agentsMd());
    result.put("soulMd", preset.soulMd());
    result.put("identityMd", preset.identityMd());
    result.put("toolsMd", preset.toolsMd());
    result.put("heartbeatMd", preset.heartbeatMd());
    result.put("userMd", preset.userMd());
    return result;
  }
}
