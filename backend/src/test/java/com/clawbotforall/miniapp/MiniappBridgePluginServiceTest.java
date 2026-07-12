package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.plugin.PluginOperationCoordinator;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MiniappBridgePluginServiceTest {

  @TempDir
  Path tempDir;

  @Mock OpenClawRuntime runtime;
  @Mock InstanceCommandService commandService;
  @Mock InstanceFileService fileService;
  @Mock InstanceMutationMapper mutationMapper;
  @Mock PluginOperationCoordinator coordinator;

  @Test
  void detectsVersionFromNpmProjectDependency() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("instance_1");
    InstancePaths paths = new InstancePaths(
        tempDir.resolve("base"), tempDir.resolve("home"), tempDir.resolve("workspace"), tempDir.resolve("logs"));
    when(fileService.paths("instance_1")).thenReturn(paths);

    Path packageJson = paths.homeDir().resolve(".openclaw/npm/projects/claw-manager-miniapp-bridge-plugin-test/package.json");
    Files.createDirectories(packageJson.getParent());
    Files.writeString(packageJson, """
        {
          "name": "openclaw-npm-project",
          "dependencies": {
            "@claw-manager/miniapp-bridge-plugin": "2026.7.13"
          }
        }
        """);

    MiniappBridgePluginService service = new MiniappBridgePluginService(
        runtime, commandService, fileService, mutationMapper, new ObjectMapper(), coordinator);

    assertThat(service.status(instance, false).installed()).isTrue();
    assertThat(service.status(instance, false).currentVersion()).isEqualTo("2026.7.13");
  }
}
