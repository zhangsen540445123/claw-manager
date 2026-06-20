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

import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
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
        executor
    );
  }

  @Test
  void startInstallReturnsInstallingAndRunsInstallInBackground() throws Exception {
    InstanceEntity instance = instance();
    InstancePaths paths = new InstancePaths(
        tempDir.resolve("inst_1"),
        tempDir.resolve("inst_1").resolve("home"),
        tempDir.resolve("inst_1").resolve("workspace"),
        tempDir.resolve("inst_1").resolve("logs")
    );
    Files.createDirectories(
        paths.homeDir().resolve(".openclaw").resolve("npm").resolve("projects").resolve("tencent-weixin-openclaw-weixin")
    );
    Files.writeString(
        paths.homeDir()
            .resolve(".openclaw")
            .resolve("npm")
            .resolve("projects")
            .resolve("tencent-weixin-openclaw-weixin")
            .resolve("package.json"),
        "{\"private\":true,\"dependencies\":{\"@tencent-weixin/openclaw-weixin\":\"2.5.0\"}}"
    );
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
            "npm:@tencent-weixin/openclaw-weixin",
            "--force"
        )
    );
    assertThat(status.installed()).isTrue();
    assertThat(status.currentVersion()).isEqualTo("2.5.0");
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
  void startInstallDeduplicatesConcurrentInstallForSameInstance() {
    InstanceEntity instance = instance();
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-19T00:00:00Z"));

    PublicWechatPluginStatus first = service.startInstall(instance);
    PublicWechatPluginStatus second = service.startInstall(instance);

    assertThat(first.status()).isEqualTo("installing");
    assertThat(second.status()).isEqualTo("installing");
    assertThat(executor.size()).isEqualTo(1);
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
