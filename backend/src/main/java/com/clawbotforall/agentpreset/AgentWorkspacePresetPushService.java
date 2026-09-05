package com.clawbotforall.agentpreset;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 后台手动将最新 Agent 工作区预设推送到所有实例中已物化的 user_* Agent 工作区。
 *
 * <p>与插件首次 provisioning 的“只写缺失”播种不同，这里会直接在主机构建层全量覆盖 6 个工作区 md
 * （AGENTS/SOUL/IDENTITY/TOOLS/HEARTBEAT/USER.md），不依赖实例容器在线，也不会改动插件的播种语义。
 */
@Service
public class AgentWorkspacePresetPushService {

  private static final Pattern WORKSPACE_AGENT_DIR =
      Pattern.compile("workspace-user_[0-9a-f]{32}");
  private static final String WORKSPACE_PREFIX = "workspace-";
  private static final Path STATE_FILE = Path.of(".openclaw", "workspace-state.json");
  private static final List<String> MD_FILES = List.of(
      "AGENTS.md", "SOUL.md", "IDENTITY.md", "TOOLS.md", "HEARTBEAT.md", "USER.md");

  private final AgentWorkspacePresetMapper mapper;
  private final InstanceAggregateMapper instances;
  private final AgentWorkspacePresetProvider provider;
  private final Path dataDir;

  @Autowired
  public AgentWorkspacePresetPushService(
      AgentWorkspacePresetMapper mapper,
      InstanceAggregateMapper instances,
      AgentWorkspacePresetProvider provider,
      ClawbotProperties properties
  ) {
    this(mapper, instances, provider, Path.of(properties.paths().dataDir()));
  }

  AgentWorkspacePresetPushService(
      AgentWorkspacePresetMapper mapper,
      InstanceAggregateMapper instances,
      AgentWorkspacePresetProvider provider,
      Path dataDir
  ) {
    this.mapper = mapper;
    this.instances = instances;
    this.provider = provider;
    this.dataDir = dataDir;
  }

  /**
   * 使用最新预设覆盖全部实例中已物化的 Agent 工作区。逐实例/逐目录容错：失败只记入结果，不中断整体。
   */
  public AgentWorkspacePresetPushResult push() {
    if (mapper.findGlobal() == null) {
      throw new ApiException(HttpStatus.BAD_REQUEST,
          "尚未保存过 Agent 工作区预设，不能推送到已有 Agent。请先保存一次预设。");
    }
    AgentWorkspacePreset preset = provider.current();
    List<AgentWorkspacePresetPushResult.Failure> failures = new ArrayList<>();
    int instancesProcessed = 0;
    int agentsUpdated = 0;
    int filesWritten = 0;
    for (InstanceEntity instance : instances.listAll()) {
      instancesProcessed++;
      Path openClawDir = dataDir.resolve("instances")
          .resolve(instance.getId())
          .resolve("home")
          .resolve(".openclaw");
      for (Path workspace : listMaterializedWorkspaces(openClawDir, instance.getId(), failures)) {
        String agentId = agentIdOf(workspace.getFileName().toString());
        try {
          filesWritten += writePresetFiles(workspace, preset);
          agentsUpdated++;
        } catch (IOException error) {
          failures.add(new AgentWorkspacePresetPushResult.Failure(
              instance.getId(), agentId, error.getMessage() == null
                  ? "覆盖 Agent 工作区失败。" : error.getMessage()));
        }
      }
    }
    return new AgentWorkspacePresetPushResult(
        preset.version(), instancesProcessed, agentsUpdated, filesWritten, failures);
  }

  private List<Path> listMaterializedWorkspaces(
      Path openClawDir,
      String instanceId,
      List<AgentWorkspacePresetPushResult.Failure> failures
  ) {
    List<Path> result = new ArrayList<>();
    if (!Files.isDirectory(openClawDir)) {
      return result;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(openClawDir)) {
      for (Path entry : entries) {
        if (!Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        String name = entry.getFileName().toString();
        if (!WORKSPACE_AGENT_DIR.matcher(name).matches()) {
          continue;
        }
        if (isMaterialized(entry)) {
          result.add(entry);
        }
      }
    } catch (IOException error) {
      failures.add(new AgentWorkspacePresetPushResult.Failure(
          instanceId, "", error.getMessage() == null
              ? "读取 Agent 工作区目录失败。" : error.getMessage()));
    }
    return result;
  }

  private static boolean isMaterialized(Path workspace) {
    if (Files.isRegularFile(workspace.resolve(STATE_FILE))) {
      return true;
    }
    for (String file : MD_FILES) {
      if (Files.isRegularFile(workspace.resolve(file))) {
        return true;
      }
    }
    return false;
  }

  private static int writePresetFiles(Path workspace, AgentWorkspacePreset preset) throws IOException {
    int written = 0;
    written += writeAtomically(workspace.resolve("AGENTS.md"), preset.agentsMd());
    written += writeAtomically(workspace.resolve("SOUL.md"), preset.soulMd());
    written += writeAtomically(workspace.resolve("IDENTITY.md"), preset.identityMd());
    written += writeAtomically(workspace.resolve("TOOLS.md"), preset.toolsMd());
    written += writeAtomically(workspace.resolve("HEARTBEAT.md"), preset.heartbeatMd());
    written += writeAtomically(workspace.resolve("USER.md"), preset.userMd());
    return written;
  }

  private static int writeAtomically(Path target, String content) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(temporary, content, StandardCharsets.UTF_8);
    try {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return 1;
  }

  private static String agentIdOf(String directoryName) {
    return directoryName.startsWith(WORKSPACE_PREFIX)
        ? directoryName.substring(WORKSPACE_PREFIX.length())
        : directoryName;
  }
}
