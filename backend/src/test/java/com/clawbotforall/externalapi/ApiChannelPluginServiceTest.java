package com.clawbotforall.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.plugin.PluginOperationCoordinator;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiChannelPluginServiceTest {

  @TempDir
  Path tempDir;

  @Mock
  OpenClawRuntime openClawRuntime;

  @Mock
  InstanceCommandService commandService;

  @Mock
  InstanceFileService fileService;

  @Mock
  InstanceMutationMapper mutationMapper;

  @Mock
  InstanceEventPublisher eventPublisher;

  @Mock
  PluginOperationCoordinator operationCoordinator;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void detectsExtensionPackageInstall() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("instance_1");
    InstancePaths paths = new InstancePaths(
        tempDir.resolve("base"),
        tempDir.resolve("home"),
        tempDir.resolve("workspace"),
        tempDir.resolve("logs")
    );
    when(fileService.paths("instance_1")).thenReturn(paths);

    Path packageJson = paths.homeDir()
        .resolve(".openclaw")
        .resolve("extensions")
        .resolve("claw-manager-api")
        .resolve("package.json");
    Files.createDirectories(packageJson.getParent());
    Files.writeString(packageJson, """
        {
          "name": "@claw-manager/openclaw-api-channel",
          "version": "2026.6.31"
        }
        """);

    ApiChannelPluginService service = new ApiChannelPluginService(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        objectMapper,
        operationCoordinator,
        Runnable::run,
        () -> List.of("2026.6.31")
    );

    assertThat(service.isInstalled(instance)).isTrue();
    assertThat(service.status(instance, false).currentVersion()).isEqualTo("2026.6.31");
  }
}
