package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceProvisioningService;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.instance.InstanceQueryService;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WechatBindServiceTest {

  @Mock
  InstanceAggregateMapper aggregateMapper;

  @Mock
  InstanceMutationMapper mutationMapper;

  @Mock
  InstanceCommandService commandService;

  @Mock
  InstanceFileService fileService;

  @Mock
  InstanceProvisioningService provisioningService;

  @Mock
  OpenClawRuntime openClawRuntime;

  @Mock
  InstanceQueryService queryService;

  @Mock
  InstanceEventPublisher eventPublisher;

  @Mock
  WechatAccountSyncService accountSyncService;

  @Mock
  RuntimeExecHandle execHandle;

  @Mock
  WechatPluginService pluginService;

  @Mock
  OpenClawGatewayRpcService gatewayRpcService;

  WechatBindService service;

  @BeforeEach
  void setUp() {
    service = new WechatBindService(
        aggregateMapper,
        mutationMapper,
        commandService,
        fileService,
        provisioningService,
        openClawRuntime,
        queryService,
        eventPublisher,
        accountSyncService,
        pluginService,
        gatewayRpcService,
        testProperties(),
        Runnable::run
    );
  }

  @Test
  void startsPluginQrLoginWithTargetAccountAndReturnsActualAccountFromCompletionEvent() {
    InstanceEntity instance = instance();
    InstancePaths paths = new InstancePaths(
        Path.of("instances", "inst_1"),
        Path.of("instances", "inst_1", "home"),
        Path.of("instances", "inst_1", "workspace"),
        Path.of("instances", "inst_1", "logs")
    );
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning()));
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-18T00:00:00Z"));
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(fileService.writeInstanceFiles(eq(instance), any())).thenReturn(paths);
    when(pluginService.isWechatPluginInstalled(instance)).thenReturn(true);
    when(openClawRuntime.startExec(
        eq(instance),
        any(List.class),
        eq(600_000L),
        anyMap(),
        any(RuntimeExecListener.class)
    )).thenAnswer(invocation -> {
      RuntimeExecListener listener = invocation.getArgument(4);
      listener.onOutput("__OPENCLAW_WECHAT_BIND__{\"type\":\"qr\",\"requestedAccountId\":\"cmwx_token_1\",\"sessionKey\":\"cmwx_token_1\",\"qrLink\":\"https://liteapp.weixin.qq.com/q/test\"}\n");
      listener.onOutput("__OPENCLAW_WECHAT_BIND__{\"type\":\"connected\",\"requestedAccountId\":\"cmwx_token_1\",\"accountId\":\"554603a4df61-im-bot\",\"rawAccountId\":\"554603a4df61@im.bot\",\"wechatUserId\":\"wechat-user\",\"baseUrl\":\"https://ilinkai.weixin.qq.com\",\"message\":\"connected\"}\n");
      listener.onComplete(0);
      return execHandle;
    });

    AtomicReference<WechatBindService.BindCompletion> completion = new AtomicReference<>();
    WechatBindService.BindStartResult result = service.startBind(instance, false, "cmwx_token_1", completion::set);

    assertThat(result.accountId()).isEqualTo("cmwx_token_1");
    assertThat(result.sessionKey()).isEqualTo("cmwx_token_1");
    assertThat(result.qrMode()).isEqualTo("link");
    assertThat(result.qrLink()).isEqualTo("https://liteapp.weixin.qq.com/q/test");
    assertThat(completion.get().requestedAccountId()).isEqualTo("cmwx_token_1");
    assertThat(completion.get().accountId()).isEqualTo("554603a4df61-im-bot");
    assertThat(completion.get().rawAccountId()).isEqualTo("554603a4df61@im.bot");
    assertThat(completion.get().wechatUserId()).isEqualTo("wechat-user");

    verify(accountSyncService).syncInstanceAccounts(instance);
    verify(gatewayRpcService, never()).restartWechatChannel(instance, List.of("cmwx_token_1"));
    verify(provisioningService, never()).startProvisioning(anyString());
    verify(openClawRuntime, never()).startExec(eq(instance), anyString(), anyLong(), anyMap(), any(RuntimeExecListener.class));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
    verify(openClawRuntime).startExec(
        eq(instance),
        commandCaptor.capture(),
        eq(600_000L),
        anyMap(),
        any(RuntimeExecListener.class)
    );
    assertThat(commandCaptor.getValue()).hasSize(4);
    assertThat(commandCaptor.getValue().get(0)).isEqualTo("node");
    assertThat(commandCaptor.getValue().get(1)).isEqualTo("--input-type=module");
    assertThat(commandCaptor.getValue().get(2)).isEqualTo("-e");
    assertThat(commandCaptor.getValue().get(3))
        .contains("startWeixinLoginWithQr")
        .contains("waitForWeixinLogin")
        .contains("cmwx_token_1");

    verify(mutationMapper, never()).upsertWechatAccountChannel(any());
  }

  private static InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("OpenClaw");
    instance.setStatus("running");
    return instance;
  }

  private static InstanceProvisioningEntity readyProvisioning() {
    InstanceProvisioningEntity provisioning = new InstanceProvisioningEntity();
    provisioning.setInstanceId("inst_1");
    provisioning.setStatus("ready");
    provisioning.setPercent(100);
    return provisioning;
  }

  private static ClawbotProperties testProperties() {
    return new ClawbotProperties(
        null,
        null,
        null,
        new ClawbotProperties.Runtime(
            "runner:latest",
            600_000,
            "1.0",
            "1g",
            600_000,
            120_000,
            1_800_000,
            10_000,
            5_000,
            1_000_000,
            128_000,
            List.of()
        )
    );
  }
}
