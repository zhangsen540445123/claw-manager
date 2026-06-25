package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WechatPluginServiceTest {

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
  RuntimeExecHandle execHandle;

  WechatPluginService service;
  List<List<String>> commands;
  QueuedExecutor executor;

  @BeforeEach
  void setUp() {
    commands = new ArrayList<>();
    executor = new QueuedExecutor();
    service = new WechatPluginService(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        new ObjectMapper(),
        executor,
        () -> List.of("2026.6.26", "2026.6.25", "2026.6.24", "2026.6.23", "2026.6.22", "2026.6.21")
    );
  }

  @Test
  void startInstallReturnsInstallingAndRunsInstallInBackground() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = emptyPluginPaths();
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));
    when(fileService.paths("inst_1")).thenReturn(paths);
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(openClawRuntime.startExec(eq(instance), any(List.class), anyLong(), anyMap(), any(RuntimeExecListener.class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> command = invocation.getArgument(1);
          commands.add(command);
          RuntimeExecListener listener = invocation.getArgument(4);
          writeInstalledPluginVersion(paths, "2026.6.26");
          listener.onComplete(0);
          return execHandle;
        });

    PublicWechatPluginStatus started = service.startInstall(instance);

    assertThat(started.status()).isEqualTo("installing");
    assertThat(started.installed()).isFalse();
    assertThat(commands).isEmpty();
    verify(openClawRuntime, never()).startExec(eq(instance), any(List.class), anyLong(), anyMap(), any(RuntimeExecListener.class));

    executor.runNext();

    PublicWechatPluginStatus status = service.status(instance, false);
    assertThat(commands).containsExactly(
        List.of(
            "openclaw",
            "plugins",
            "install",
            "npm:@claw-manager/openclaw-weixin",
            "--force"
        )
    );
    assertThat(status.installed()).isTrue();
    assertThat(status.currentVersion()).isEqualTo("2026.6.26");
    verify(mutationMapper).updateInstancePlugins(
        eq("inst_1"),
        eq("[\"openclaw-weixin\"]"),
        eq("{\"openclaw-weixin\":{\"enabled\":true}}"),
        any()
    );
    verify(fileService).writeInstanceFiles(instance, List.of());
    verify(eventPublisher, atLeastOnce()).publishWechatPluginUpdated(eq("inst_1"), any(PublicWechatPluginStatus.class));
  }

  @Test
  void startInstallUsesSelectedClawManagerVersion() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = emptyPluginPaths();
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));
    when(fileService.paths("inst_1")).thenReturn(paths);
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(openClawRuntime.startExec(eq(instance), any(List.class), anyLong(), anyMap(), any(RuntimeExecListener.class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> command = invocation.getArgument(1);
          commands.add(command);
          RuntimeExecListener listener = invocation.getArgument(4);
          writeInstalledPluginVersion(paths, "2026.6.23");
          listener.onComplete(0);
          return execHandle;
        });

    PublicWechatPluginStatus started = service.startInstall(instance, "2026.6.23");

    assertThat(started.status()).isEqualTo("installing");
    executor.runNext();

    assertThat(commands).containsExactly(
        List.of(
            "openclaw",
            "plugins",
            "install",
            "npm:@claw-manager/openclaw-weixin@2026.6.23",
            "--force"
        )
    );
    assertThat(service.status(instance, false).currentVersion()).isEqualTo("2026.6.23");
  }

  @Test
  void pluginVersionsExposeLatestAndRecentFiveClawManagerVersions() {
    WechatPluginVersions versions = service.versions();

    assertThat(versions.latest()).isEqualTo("2026.6.26");
    assertThat(versions.versions()).containsExactly("2026.6.26", "2026.6.25", "2026.6.24", "2026.6.23", "2026.6.22");
  }

  @Test
  void pluginVersionsReturnEmptyWhenClawManagerRegistryIsUnavailableWithoutCache() {
    service = new WechatPluginService(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        new ObjectMapper(),
        executor,
        () -> {
          throw new IllegalStateException("registry offline");
        }
    );

    WechatPluginVersions versions = service.versions();

    assertThat(versions.latest()).isBlank();
    assertThat(versions.versions()).isEmpty();
  }

  @Test
  void statusUsesCachedLatestVersionWithoutCallingOfficialRegistryAgain() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    service = new WechatPluginService(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        new ObjectMapper(),
        executor,
        () -> {
          calls.incrementAndGet();
          return List.of("2026.6.26", "2026.6.25", "2026.6.24");
        }
    );
    InstanceEntity instance = instance();
    installedPluginPaths("2026.6.24");

    assertThat(service.versions().latest()).isEqualTo("2026.6.26");
    PublicWechatPluginStatus status = service.status(instance, true);

    assertThat(calls).hasValue(1);
    assertThat(status.latestVersion()).isEqualTo("2026.6.26");
    assertThat(status.upgradable()).isTrue();
  }

  @Test
  void statusPrefersWechatPluginProjectDirectoryWhenReadingCurrentVersion() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = emptyPluginPaths();
    writeProjectPackage(paths, "aaa-unrelated-project", "9.9.9");
    writeProjectPackage(paths, "claw-manager-openclaw-weixin-7783ac86ba", "2026.6.23");
    when(fileService.paths("inst_1")).thenReturn(paths);

    PublicWechatPluginStatus status = service.status(instance, false);

    assertThat(status.currentVersion()).isEqualTo("2026.6.23");
  }

  @Test
  void statusIgnoresLegacyOfficialWechatPluginProjectDirectory() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = emptyPluginPaths();
    writeLegacyOfficialProjectPackage(paths, "tencent-weixin-openclaw-weixin-7783ac86ba", "2.4.6");
    when(fileService.paths("inst_1")).thenReturn(paths);

    PublicWechatPluginStatus status = service.status(instance, false);

    assertThat(status.installed()).isFalse();
    assertThat(status.currentVersion()).isBlank();
  }

  @Test
  void startInstallDeduplicatesConcurrentInstallForSameInstance() {
    InstanceEntity instance = instance();
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));
    when(fileService.paths("inst_1")).thenReturn(emptyPluginPaths());

    PublicWechatPluginStatus first = service.startInstall(instance);
    PublicWechatPluginStatus second = service.startInstall(instance);

    assertThat(first.status()).isEqualTo("installing");
    assertThat(second.status()).isEqualTo("installing");
    assertThat(executor.size()).isEqualTo(1);
  }

  @Test
  void startUninstallRunsUninstallInBackgroundAndDisablesPlugin() throws Exception {
    InstanceEntity instance = instance();
    instance.setPluginsAllow("[\"openclaw-weixin\",\"other-plugin\"]");
    instance.setPluginsEntries("{\"openclaw-weixin\":{\"enabled\":true},\"other-plugin\":{\"enabled\":true}}");
    InstancePaths paths = installedPluginPaths("2026.6.24");
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));
    when(fileService.paths("inst_1")).thenReturn(paths);
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(openClawRuntime.startExec(eq(instance), any(List.class), anyLong(), anyMap(), any(RuntimeExecListener.class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> command = invocation.getArgument(1);
          commands.add(command);
          RuntimeExecListener listener = invocation.getArgument(4);
          listener.onComplete(0);
          return execHandle;
        });

    PublicWechatPluginStatus started = service.startUninstall(instance);

    assertThat(started.status()).isEqualTo("uninstalling");
    executor.runNext();

    assertThat(commands).containsExactly(List.of("openclaw", "plugins", "uninstall", "openclaw-weixin", "--force"));
    verify(mutationMapper).updateInstancePlugins(
        eq("inst_1"),
        eq("[\"other-plugin\"]"),
        eq("{\"other-plugin\":{\"enabled\":true}}"),
        any()
    );
    verify(fileService).writeInstanceFiles(instance, List.of());
    verify(eventPublisher, atLeastOnce()).publishWechatPluginUpdated(eq("inst_1"), any(PublicWechatPluginStatus.class));
  }

  @Test
  void startUpgradeRunsOpenClawPluginUpdateInBackground() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = installedPluginPaths("2026.6.24");
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));
    when(fileService.paths("inst_1")).thenReturn(paths);
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(openClawRuntime.startExec(eq(instance), any(List.class), anyLong(), anyMap(), any(RuntimeExecListener.class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> command = invocation.getArgument(1);
          commands.add(command);
          RuntimeExecListener listener = invocation.getArgument(4);
          writeInstalledPluginVersion(paths, "2026.6.26");
          listener.onComplete(0);
          return execHandle;
        });

    PublicWechatPluginStatus started = service.startUpgrade(instance);

    assertThat(started.status()).isEqualTo("upgrading");
    executor.runNext();

    assertThat(commands).containsExactly(
        List.of(
            "openclaw",
            "plugins",
            "install",
            "npm:@claw-manager/openclaw-weixin@2026.6.26",
            "--force"
        )
    );
    verify(mutationMapper).updateInstancePlugins(
        eq("inst_1"),
        eq("[\"openclaw-weixin\"]"),
        eq("{\"openclaw-weixin\":{\"enabled\":true}}"),
        any()
    );
    verify(eventPublisher, atLeastOnce()).publishWechatPluginUpdated(eq("inst_1"), any(PublicWechatPluginStatus.class));
  }

  @Test
  void startUpgradeRejectsSameOrLowerVersion() throws Exception {
    InstanceEntity instance = instance();
    installedPluginPaths("2026.6.24");
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));

    assertThatThrownBy(() -> service.startUpgrade(instance, "2026.6.24"))
        .hasMessageContaining("目标版本必须高于当前版本");
    assertThatThrownBy(() -> service.startUpgrade(instance, "2026.6.23"))
        .hasMessageContaining("目标版本必须高于当前版本");
    assertThat(commands).isEmpty();
  }

  @Test
  void startReinstallOverwritesInstalledPluginWithoutUninstalling() throws Exception {
    InstanceEntity instance = instance();
    instance.setPluginsAllow("[\"openclaw-weixin\"]");
    instance.setPluginsEntries("{\"openclaw-weixin\":{\"enabled\":true}}");
    InstancePaths paths = installedPluginPaths("2026.6.24");
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(openClawRuntime.startExec(eq(instance), any(List.class), anyLong(), anyMap(), any(RuntimeExecListener.class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> command = invocation.getArgument(1);
          commands.add(command);
          RuntimeExecListener listener = invocation.getArgument(4);
          writeInstalledPluginVersion(paths, "2026.6.24");
          listener.onComplete(0);
          return execHandle;
        });

    PublicWechatPluginStatus started = service.startReinstall(instance, "");

    assertThat(started.status()).isEqualTo("reinstalling");
    executor.runNext();

    assertThat(commands).containsExactly(
        List.of(
            "openclaw",
            "plugins",
            "install",
            "npm:@claw-manager/openclaw-weixin@2026.6.24",
            "--force"
        )
    );
    verify(openClawRuntime, never()).startExec(
        eq(instance),
        eq(List.of("openclaw", "plugins", "uninstall", "openclaw-weixin", "--force")),
        anyLong(),
        anyMap(),
        any(RuntimeExecListener.class)
    );
    verify(mutationMapper).updateInstancePlugins(
        eq("inst_1"),
        eq("[\"openclaw-weixin\"]"),
        eq("{\"openclaw-weixin\":{\"enabled\":true}}"),
        any()
    );
  }

  @Test
  void pluginTasksDeduplicateAcrossOperationsForSameInstance() throws Exception {
    InstanceEntity instance = instance();
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));
    when(fileService.paths("inst_1")).thenReturn(emptyPluginPaths());

    PublicWechatPluginStatus first = service.startInstall(instance);
    PublicWechatPluginStatus second = service.startUpgrade(instance);

    assertThat(first.status()).isEqualTo("installing");
    assertThat(second.status()).isEqualTo("installing");
    assertThat(executor.size()).isEqualTo(1);
  }

  @Test
  void startInstallRejectsWhenOpenVikingPluginTaskIsRunningForSameInstance() {
    PluginOperationCoordinator coordinator = new PluginOperationCoordinator();
    coordinator.tryStart("inst_1", "OpenViking 插件");
    service = new WechatPluginService(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        new ObjectMapper(),
        executor,
        () -> List.of("2026.6.26", "2026.6.25"),
        coordinator
    );
    InstanceEntity instance = instance();
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));

    assertThatThrownBy(() -> service.startInstall(instance))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("OpenViking 插件");
  }

  private InstancePaths emptyPluginPaths() {
    return new InstancePaths(
        tempDir.resolve("inst_1"),
        tempDir.resolve("inst_1").resolve("home"),
        tempDir.resolve("inst_1").resolve("workspace"),
        tempDir.resolve("inst_1").resolve("logs")
    );
  }

  private InstancePaths installedPluginPaths(String version) throws Exception {
    InstancePaths paths = emptyPluginPaths();
    writeInstalledPluginVersion(paths, version);
    when(fileService.paths("inst_1")).thenReturn(paths);
    return paths;
  }

  private void writeInstalledPluginVersion(InstancePaths paths, String version) throws Exception {
    writeProjectPackage(paths, "claw-manager-openclaw-weixin", version);
  }

  private void writeProjectPackage(InstancePaths paths, String projectName, String version) throws Exception {
    Files.createDirectories(
        paths.homeDir().resolve(".openclaw").resolve("npm").resolve("projects").resolve(projectName)
    );
    Files.writeString(
        paths.homeDir()
            .resolve(".openclaw")
            .resolve("npm")
            .resolve("projects")
            .resolve(projectName)
            .resolve("package.json"),
        "{\"private\":true,\"dependencies\":{\"@claw-manager/openclaw-weixin\":\"" + version + "\"}}"
    );
  }

  private void writeLegacyOfficialProjectPackage(InstancePaths paths, String projectName, String version) throws Exception {
    Files.createDirectories(
        paths.homeDir().resolve(".openclaw").resolve("npm").resolve("projects").resolve(projectName)
    );
    Files.writeString(
        paths.homeDir()
            .resolve(".openclaw")
            .resolve("npm")
            .resolve("projects")
            .resolve(projectName)
            .resolve("package.json"),
        "{\"private\":true,\"dependencies\":{\"@tencent-weixin/openclaw-weixin\":\"" + version + "\"}}"
    );
  }

  private static InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("实例一");
    instance.setStatus("running");
    instance.setPluginsAllow("[]");
    instance.setPluginsEntries("{}");
    return instance;
  }

  private static final class QueuedExecutor implements Executor {

    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    void runNext() {
      tasks.removeFirst().run();
    }

    int size() {
      return tasks.size();
    }
  }
}
