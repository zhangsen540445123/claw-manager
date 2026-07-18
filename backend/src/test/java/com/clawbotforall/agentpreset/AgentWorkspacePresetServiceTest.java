package com.clawbotforall.agentpreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AgentWorkspacePresetServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void returnsBuiltInPresetWhenDatabaseIsEmpty() {
    AgentWorkspacePresetMapper mapper = Mockito.mock(AgentWorkspacePresetMapper.class);
    AgentWorkspacePresetService service = new AgentWorkspacePresetService(
        mapper,
        Mockito.mock(InstanceAggregateMapper.class),
        new AgentWorkspacePresetSnapshotWriter(tempDir)
    );

    PublicAgentWorkspacePreset preset = service.publicPreset();

    assertThat(preset.version()).isZero();
    assertThat(preset.agentsMd()).contains("OpenViking");
    assertThat(preset.userMd()).doesNotContain("openid");
    verify(mapper, never()).upsert(any());
  }

  @Test
  void updatesPresetAndRefreshesOnlyInstanceSeedSnapshots() throws Exception {
    AgentWorkspacePresetMapper mapper = Mockito.mock(AgentWorkspacePresetMapper.class);
    InstanceAggregateMapper instances = Mockito.mock(InstanceAggregateMapper.class);
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst-1");
    when(instances.listAll()).thenReturn(List.of(instance));
    AgentWorkspacePresetSnapshotWriter writer = new AgentWorkspacePresetSnapshotWriter(tempDir);
    AgentWorkspacePresetService service = new AgentWorkspacePresetService(mapper, instances, writer);

    PublicAgentWorkspacePreset updated = service.update(Map.of(
        "agentsMd", "# Agent rules",
        "soulMd", "# Soul",
        "identityMd", "# Identity",
        "toolsMd", "# Tools",
        "heartbeatMd", "# Heartbeat",
        "userMd", "# User"
    ));

    assertThat(updated.version()).isEqualTo(1);
    ArgumentCaptor<AgentWorkspacePresetEntity> saved = ArgumentCaptor.forClass(AgentWorkspacePresetEntity.class);
    verify(mapper).upsert(saved.capture());
    assertThat(saved.getValue().getAgentsMd()).isEqualTo("# Agent rules\n");
    assertThat(writer.readSnapshot("inst-1"))
        .containsEntry("version", 1)
        .containsEntry("soulMd", "# Soul\n");
  }
}
