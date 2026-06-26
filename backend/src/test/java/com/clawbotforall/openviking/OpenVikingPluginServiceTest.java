package com.clawbotforall.openviking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.plugin.PluginOperationCoordinator;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenVikingPluginServiceTest {

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
  OpenVikingSettingsService settingsService;

  @Mock
  RuntimeExecHandle execHandle;

  OpenVikingPluginService service;
  QueuedExecutor executor;
  List<List<String>> commands;

  @BeforeEach
  void setUp() {
    executor = new QueuedExecutor();
    commands = new ArrayList<>();
    service = new OpenVikingPluginService(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        new ObjectMapper(),
        settingsService,
        executor,
        () -> List.of("2026.6.34", "2026.6.29")
    );
  }

  @Test
  void installUsesNpmPackageThenRunsOpenVikingSetup() {
    InstanceEntity instance = instance();
    when(settingsService.effectiveSettings()).thenReturn(settings());
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(openClawRuntime.startExec(eq(instance), anyList(), anyLong(), anyMap(), any(RuntimeExecListener.class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> command = invocation.getArgument(1);
          commands.add(command);
          RuntimeExecListener listener = invocation.getArgument(4);
          listener.onComplete(0);
          return execHandle;
        });

    PublicOpenVikingPluginStatus started = service.startInstall(instance, "2026.6.34");
    executor.runNext();

    assertThat(started.status()).isEqualTo("installing");
    assertThat(commands).containsExactly(
        List.of("openclaw", "plugins", "install", "npm:@claw-manager/openviking-openclaw-plugin@2026.6.34", "--force"),
        List.of(
            "openclaw",
            "openviking",
            "setup",
            "--base-url",
            "http://openviking:1933",
            "--account-id",
            "claw-manager",
            "--allow-offline",
            "--force-slot",
            "--json"
        )
    );
    verify(mutationMapper).updateInstancePlugins(
        eq("inst_1"),
        eq("[\"openviking\"]"),
        eq("{\"openviking\":{\"enabled\":true,\"config\":{\"mode\":\"remote\",\"baseUrl\":\"http://openviking:1933\",\"accountId\":\"claw-manager\",\"identityHashSecret\":\"${OPENVIKING_IDENTITY_HASH_SECRET}\",\"peer_role\":\"assistant\"}}}"),
        any()
    );
    verify(fileService).writeInstanceFiles(instance, List.of());
  }

  @Test
  void installRejectsMissingBaseUrl() {
    InstanceEntity instance = instance();
    when(settingsService.effectiveSettings()).thenReturn(new OpenVikingEffectiveSettings("", false, "claw-manager", "secret", "npm:@claw-manager/openviking-openclaw-plugin@2026.6.34", "root-key", "broker-token", "http://claw-manager-api:8080"));

    assertThatThrownBy(() -> service.startInstall(instance, "2026.6.34"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("OpenViking Base URL");
  }

  @Test
  void installRejectsMissingRootKey() {
    InstanceEntity instance = instance();
    when(settingsService.effectiveSettings()).thenReturn(new OpenVikingEffectiveSettings(
        "http://openviking:1933",
        false,
        "claw-manager",
        "secret",
        "npm:@claw-manager/openviking-openclaw-plugin@2026.6.34",
        "",
        "broker-token",
        "http://claw-manager-api:8080"
    ));

    assertThatThrownBy(() -> service.startInstall(instance, "2026.6.34"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Root API Key");
  }

  @Test
  void installDoesNotRejectApiKeyMode() {
    InstanceEntity instance = instance();
    when(settingsService.effectiveSettings()).thenReturn(settings());
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));

    PublicOpenVikingPluginStatus started = service.startInstall(instance, "2026.6.34");

    assertThat(started.status()).isEqualTo("installing");
  }

  @Test
  void installRejectsWhenWechatPluginTaskIsRunningForSameInstance() {
    PluginOperationCoordinator coordinator = new PluginOperationCoordinator();
    coordinator.tryStart("inst_1", "微信插件");
    service = new OpenVikingPluginService(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        new ObjectMapper(),
        settingsService,
        executor,
        () -> List.of("2026.6.34", "2026.6.29"),
        coordinator
    );
    InstanceEntity instance = instance();
    when(settingsService.effectiveSettings()).thenReturn(settings());
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));

    assertThatThrownBy(() -> service.startInstall(instance, "2026.6.34"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("微信插件");
  }

  @Test
  void versionsUsesCacheUnlessForceRefreshIsRequested() {
    AtomicInteger calls = new AtomicInteger();
    service = new OpenVikingPluginService(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        new ObjectMapper(),
        settingsService,
        executor,
        () -> List.of("2026.6." + calls.incrementAndGet())
    );

    OpenVikingPluginVersions first = service.versions(false);
    OpenVikingPluginVersions cached = service.versions(false);
    OpenVikingPluginVersions refreshed = service.versions(true);

    assertThat(first.latest()).isEqualTo("2026.6.1");
    assertThat(cached.latest()).isEqualTo("2026.6.1");
    assertThat(refreshed.latest()).isEqualTo("2026.6.2");
    assertThat(calls).hasValue(2);
  }

  private static OpenVikingEffectiveSettings settings() {
    return new OpenVikingEffectiveSettings(
        "http://openviking:1933",
        false,
        "claw-manager",
        "secret",
        "npm:@claw-manager/openviking-openclaw-plugin@2026.6.34",
        "root-key",
        "broker-token",
        "http://claw-manager-api:8080"
    );
  }

  private static InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("测试实例");
    instance.setStatus("running");
    instance.setPluginsAllow("[]");
    instance.setPluginsEntries("{}");
    return instance;
  }

  static class QueuedExecutor implements Executor {
    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    void runNext() {
      tasks.removeFirst().run();
    }
  }
}
