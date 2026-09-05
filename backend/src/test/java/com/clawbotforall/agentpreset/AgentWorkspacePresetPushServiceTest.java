package com.clawbotforall.agentpreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

class AgentWorkspacePresetPushServiceTest {

  @TempDir
  Path tempDir;

  private static final String HEX_A = "11111111111111111111111111111111";
  private static final String HEX_B = "22222222222222222222222222222222";
  private static final String HEX_C = "33333333333333333333333333333333";

  private static final AgentWorkspacePreset NEW_PRESET = new AgentWorkspacePreset(
      9,
      "# New agents\n",
      "# New soul\n",
      "# New identity\n",
      "# New tools\n",
      "# New heartbeat\n",
      "# New user\n"
  );

  @Test
  void pushesPresetToEveryMaterializedAgentWorkspaceAcrossInstances() throws Exception {
    AgentWorkspacePresetMapper mapper = Mockito.mock(AgentWorkspacePresetMapper.class);
    when(mapper.findGlobal()).thenReturn(persisted(9));
    InstanceAggregateMapper instances = Mockito.mock(InstanceAggregateMapper.class);
    InstanceEntity first = instance("inst-1");
    InstanceEntity second = instance("inst-2");
    when(instances.listAll()).thenReturn(List.of(first, second));

    Path firstAgent = workspace("inst-1", "user_" + HEX_A);
    Files.createDirectories(firstAgent.resolve(".openclaw"));
    Files.writeString(firstAgent.resolve(".openclaw").resolve("workspace-state.json"),
        "{ \"setupCompletedAt\": \"x\" }", StandardCharsets.UTF_8);
    writeLegacyFiles(firstAgent, "old-1");

    Path secondAgent = workspace("inst-2", "user_" + HEX_B);
    writeLegacyFiles(secondAgent, "old-2");

    Path emptyAgent = workspace("inst-1", "user_" + HEX_C);
    Files.writeString(emptyAgent.resolve("notes.txt"), "keep", StandardCharsets.UTF_8);

    Path managerDir = tempDir.resolve("instances").resolve("inst-1").resolve("home")
        .resolve(".openclaw").resolve("claw-manager");
    Files.createDirectories(managerDir);
    Files.writeString(managerDir.resolve("workspace-preset.json"), "keep", StandardCharsets.UTF_8);
    Path agentsDir = tempDir.resolve("instances").resolve("inst-1").resolve("home")
        .resolve(".openclaw").resolve("agents").resolve("user_agent").resolve("agent");
    Files.createDirectories(agentsDir);
    Files.writeString(agentsDir.resolve("AGENTS.md"), "# keep agents\n", StandardCharsets.UTF_8);

    AgentWorkspacePresetPushService service = new AgentWorkspacePresetPushService(
        mapper, instances, () -> NEW_PRESET, tempDir);

    AgentWorkspacePresetPushResult result = service.push();

    assertThat(result.version()).isEqualTo(9);
    assertThat(result.instancesProcessed()).isEqualTo(2);
    assertThat(result.agentsUpdated()).isEqualTo(2);
    assertThat(result.filesWritten()).isEqualTo(12);
    assertThat(result.failures()).isEmpty();

    assertWorkspaceHasPreset(firstAgent);
    assertWorkspaceHasPreset(secondAgent);
    assertThat(Files.readString(firstAgent.resolve(".openclaw").resolve("workspace-state.json")))
        .contains("setupCompletedAt");
    assertThat(Files.readString(emptyAgent.resolve("notes.txt"))).isEqualTo("keep");
    assertThat(Files.readString(managerDir.resolve("workspace-preset.json"))).isEqualTo("keep");
    assertThat(Files.readString(agentsDir.resolve("AGENTS.md"))).isEqualTo("# keep agents\n");
  }

  @Test
  void countsInstancesWithoutAgentWorkspacesAndLeavesThemUntouched() throws Exception {
    AgentWorkspacePresetMapper mapper = Mockito.mock(AgentWorkspacePresetMapper.class);
    when(mapper.findGlobal()).thenReturn(persisted(3));
    InstanceAggregateMapper instances = Mockito.mock(InstanceAggregateMapper.class);
    when(instances.listAll()).thenReturn(List.of(instance("inst-1"), instance("inst-3")));

    AgentWorkspacePresetPushService service = new AgentWorkspacePresetPushService(
        mapper, instances, () -> NEW_PRESET, tempDir);

    AgentWorkspacePresetPushResult result = service.push();

    assertThat(result.version()).isEqualTo(9);
    assertThat(result.instancesProcessed()).isEqualTo(2);
    assertThat(result.agentsUpdated()).isZero();
    assertThat(result.filesWritten()).isZero();
    assertThat(result.failures()).isEmpty();
  }

  @Test
  void rejectsPushWhenNoPresetWasEverSaved() throws Exception {
    AgentWorkspacePresetMapper mapper = Mockito.mock(AgentWorkspacePresetMapper.class);
    when(mapper.findGlobal()).thenReturn(null);
    InstanceAggregateMapper instances = Mockito.mock(InstanceAggregateMapper.class);
    when(instances.listAll()).thenReturn(List.of(instance("inst-1")));
    Path agent = workspace("inst-1", "user_" + HEX_A);
    Files.createDirectories(agent);
    Files.writeString(agent.resolve("AGENTS.md"), "# keep me\n", StandardCharsets.UTF_8);

    AgentWorkspacePresetPushService service = new AgentWorkspacePresetPushService(
        mapper, instances, () -> NEW_PRESET, tempDir);

    assertThatThrownBy(service::push)
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(Files.readString(agent.resolve("AGENTS.md"))).isEqualTo("# keep me\n");
  }

  private Path workspace(String instanceId, String name) throws Exception {
    Path dir = tempDir.resolve("instances").resolve(instanceId).resolve("home")
        .resolve(".openclaw").resolve("workspace-" + name);
    Files.createDirectories(dir);
    return dir;
  }

  private static void writeLegacyFiles(Path workspace, String suffix) throws Exception {
    Files.writeString(workspace.resolve("AGENTS.md"), "# Old agents " + suffix + "\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("SOUL.md"), "# Old soul " + suffix + "\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("IDENTITY.md"), "# Old identity " + suffix + "\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("TOOLS.md"), "# Old tools " + suffix + "\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("HEARTBEAT.md"), "# Old heartbeat " + suffix + "\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("USER.md"), "# Old user " + suffix + "\n", StandardCharsets.UTF_8);
  }

  private static void assertWorkspaceHasPreset(Path workspace) throws Exception {
    assertThat(Files.readString(workspace.resolve("AGENTS.md"))).isEqualTo("# New agents\n");
    assertThat(Files.readString(workspace.resolve("SOUL.md"))).isEqualTo("# New soul\n");
    assertThat(Files.readString(workspace.resolve("IDENTITY.md"))).isEqualTo("# New identity\n");
    assertThat(Files.readString(workspace.resolve("TOOLS.md"))).isEqualTo("# New tools\n");
    assertThat(Files.readString(workspace.resolve("HEARTBEAT.md"))).isEqualTo("# New heartbeat\n");
    assertThat(Files.readString(workspace.resolve("USER.md"))).isEqualTo("# New user\n");
  }

  private static AgentWorkspacePresetEntity persisted(int version) {
    AgentWorkspacePresetEntity entity = new AgentWorkspacePresetEntity();
    entity.setId(AgentWorkspacePresetService.GLOBAL_ID);
    entity.setVersion(version);
    entity.setAgentsMd("# New agents\n");
    entity.setSoulMd("# New soul\n");
    entity.setIdentityMd("# New identity\n");
    entity.setToolsMd("# New tools\n");
    entity.setHeartbeatMd("# New heartbeat\n");
    entity.setUserMd("# New user\n");
    entity.setUpdatedAt("2026-09-05T00:00:00Z");
    return entity;
  }

  private static InstanceEntity instance(String id) {
    InstanceEntity entity = new InstanceEntity();
    entity.setId(id);
    return entity;
  }
}
