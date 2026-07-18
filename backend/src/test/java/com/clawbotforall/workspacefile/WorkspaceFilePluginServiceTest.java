package com.clawbotforall.workspacefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.plugin.PluginOperationCoordinator;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceFilePluginServiceTest {

  @TempDir
  Path tempDir;

  @Mock OpenClawRuntime runtime;
  @Mock InstanceCommandService commandService;
  @Mock InstanceFileService fileService;
  @Mock InstanceMutationMapper mutationMapper;
  @Mock InstanceEventPublisher eventPublisher;
  @Mock PluginOperationCoordinator coordinator;
  @Mock RuntimeExecHandle execHandle;

  private QueuedExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new QueuedExecutor();
  }

  @Test
  void detectsVersionFromNpmProjectDependency() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("instance_1");
    InstancePaths paths = new InstancePaths(
        tempDir.resolve("base"), tempDir.resolve("home"), tempDir.resolve("workspace"), tempDir.resolve("logs"));
    when(fileService.paths("instance_1")).thenReturn(paths);

    Path packageJson = paths.homeDir().resolve(".openclaw/npm/projects/claw-manager-workspace-file-plugin-test/package.json");
    Files.createDirectories(packageJson.getParent());
    Files.writeString(packageJson, """
        {
          "name": "openclaw-npm-project",
          "dependencies": {
            "@claw-manager/workspace-file-plugin": "2026.7.13"
          }
        }
        """);

    WorkspaceFilePluginService service = service(() -> List.of("2026.7.16"));

    assertThat(service.status(instance, false).installed()).isTrue();
    assertThat(service.status(instance, false).currentVersion()).isEqualTo("2026.7.13");
  }

  @Test
  void versionsExposeLatestAndRecentFivePublishedVersions() {
    WorkspaceFilePluginService service = service(() -> List.of(
        "2026.7.13", "invalid", "2026.7.16", "2026.7.12", "2026.7.15", "2026.7.14", "2026.7.11"));

    var versions = service.versions(false);

    assertThat(versions.latest()).isEqualTo("2026.7.16");
    assertThat(versions.versions()).containsExactly(
        "2026.7.16", "2026.7.15", "2026.7.14", "2026.7.13", "2026.7.12");
  }

  @Test
  void versionsUseCacheUnlessForceRefreshIsRequested() {
    AtomicInteger calls = new AtomicInteger();
    WorkspaceFilePluginService service = service(() -> List.of("2026.7." + calls.incrementAndGet()));

    var first = service.versions(false);
    var cached = service.versions(false);
    var refreshed = service.versions(true);

    assertThat(first.latest()).isEqualTo("2026.7.1");
    assertThat(cached.latest()).isEqualTo("2026.7.1");
    assertThat(refreshed.latest()).isEqualTo("2026.7.2");
    assertThat(calls).hasValue(2);
  }

  @Test
  void versionsKeepCachedValueWhenRegistryRefreshFails() {
    AtomicInteger calls = new AtomicInteger();
    WorkspaceFilePluginService service = service(() -> {
      if (calls.incrementAndGet() == 1) {
        return List.of("2026.7.16");
      }
      throw new IllegalStateException("registry offline");
    });

    assertThat(service.versions(false).latest()).isEqualTo("2026.7.16");
    assertThat(service.versions(true).latest()).isEqualTo("2026.7.16");
  }

  @Test
  void statusUsesSemanticVersionComparison() throws Exception {
    InstanceEntity instance = instanceWithNpmProject(
        "claw-manager-workspace-file-plugin-test",
        "npm:@claw-manager/workspace-file-plugin@2026.7.15"
    );
    WorkspaceFilePluginService service = service(() -> List.of("2026.7.16"));
    service.versions(false);

    var status = service.status(instance, true);

    assertThat(status.currentVersion()).isEqualTo("2026.7.15");
    assertThat(status.latestVersion()).isEqualTo("2026.7.16");
    assertThat(status.upgradable()).isTrue();
  }

  @Test
  void statusIgnoresOtherNpmProjects() throws Exception {
    InstanceEntity instance = instanceWithNpmProject(
        "claw-manager-openclaw-api-channel-test",
        "2026.7.16"
    );

    var status = service(() -> List.of("2026.7.16")).status(instance, false);

    assertThat(status.installed()).isFalse();
    assertThat(status.currentVersion()).isBlank();
  }

  @Test
  void upgradePublishesTaskProgressAndUsesSelectedVersion() throws Exception {
    InstanceEntity instance = instanceWithNpmProject(
        "claw-manager-workspace-file-plugin-test",
        "2026.7.15"
    );
    instance.setPluginsAllow("[]");
    instance.setPluginsEntries("{}");
    when(runtime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));
    when(coordinator.tryStart("instance_1", "工作区文件插件")).thenReturn(true);
    when(commandService.listModels("instance_1")).thenReturn(List.of());
    when(runtime.startExec(eq(instance), anyList(), anyLong(), anyMap(), any(RuntimeExecListener.class)))
        .thenAnswer(invocation -> {
          RuntimeExecListener listener = invocation.getArgument(4);
          listener.onOutput("installed workspace file 2026.7.16");
          listener.onComplete(0);
          return execHandle;
        });
    WorkspaceFilePluginService service = service(executor, () -> List.of("2026.7.16"));

    var started = service.startUpgrade(instance, "2026.7.16");
    executor.runNext();

    assertThat(started.status()).isEqualTo("upgrading");
    verify(runtime).startExec(
        eq(instance),
        eq(List.of(
            "openclaw", "plugins", "install",
            "npm:@claw-manager/workspace-file-plugin@2026.7.16", "--force")),
        anyLong(),
        anyMap(),
        any(RuntimeExecListener.class)
    );
    ArgumentCaptor<com.clawbotforall.externalapi.PublicApiChannelPluginStatus> statuses =
        ArgumentCaptor.forClass(com.clawbotforall.externalapi.PublicApiChannelPluginStatus.class);
    verify(eventPublisher, times(2)).publishWorkspaceFilePluginUpdated(eq("instance_1"), statuses.capture());
    assertThat(statuses.getAllValues()).extracting(com.clawbotforall.externalapi.PublicApiChannelPluginStatus::status)
        .containsExactly("upgrading", "installed");
    assertThat(statuses.getAllValues().getLast().outputSnippet()).contains("installed workspace file 2026.7.16");
    verify(coordinator).finish("instance_1", "工作区文件插件");
  }

  private InstanceEntity instanceWithNpmProject(String projectName, String dependencyVersion) throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("instance_1");
    InstancePaths paths = new InstancePaths(
        tempDir.resolve("base"), tempDir.resolve("home"), tempDir.resolve("workspace"), tempDir.resolve("logs"));
    when(fileService.paths("instance_1")).thenReturn(paths);
    Path packageJson = paths.homeDir().resolve(".openclaw/npm/projects").resolve(projectName).resolve("package.json");
    Files.createDirectories(packageJson.getParent());
    Files.writeString(packageJson, """
        {
          "name": "openclaw-npm-project",
          "dependencies": {
            "@claw-manager/workspace-file-plugin": "%s"
          }
        }
        """.formatted(dependencyVersion));
    return instance;
  }

  private WorkspaceFilePluginService service(java.util.function.Supplier<List<String>> versionSupplier) {
    return service(Runnable::run, versionSupplier);
  }

  private WorkspaceFilePluginService service(
      Executor taskExecutor,
      java.util.function.Supplier<List<String>> versionSupplier
  ) {
    return new WorkspaceFilePluginService(
        runtime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        new ObjectMapper(),
        coordinator,
        taskExecutor,
        versionSupplier
    );
  }

  private static final class QueuedExecutor implements Executor {
    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    private void runNext() {
      tasks.removeFirst().run();
    }
  }
}
