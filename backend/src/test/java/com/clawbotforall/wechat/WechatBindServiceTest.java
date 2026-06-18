package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import com.clawbotforall.instance.InstanceWechatBindingEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
        testProperties()
    );
  }

  @Test
  void restartsGatewayWhenLoginSavedAuthButRunningGatewayCannotStartChannel() {
    InstanceEntity instance = instance();
    InstancePaths paths = new InstancePaths(
        Path.of("instances", "inst_1"),
        Path.of("instances", "inst_1", "home"),
        Path.of("instances", "inst_1", "workspace"),
        Path.of("instances", "inst_1", "logs")
    );
    ArgumentCaptor<RuntimeExecListener> listenerCaptor = ArgumentCaptor.forClass(RuntimeExecListener.class);
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning()));
    when(aggregateMapper.listWechatBindingByInstanceIds(List.of("inst_1"))).thenReturn(List.of());
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-18T00:00:00Z"));
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(fileService.writeInstanceFiles(eq(instance), any())).thenReturn(paths);
    when(queryService.findPublicInstance(eq("inst_1"), any())).thenReturn(Optional.empty());
    when(openClawRuntime.startExec(
        eq(instance),
        anyString(),
        anyLong(),
        anyMap(),
        listenerCaptor.capture()
    )).thenReturn(execHandle);

    service.startBind(instance, false);
    listenerCaptor.getValue().onOutput("""
        已将此 OpenClaw 连接到微信。
        Local login saved auth for openclaw-weixin/default, but the running gateway did not restart it: invalid channels.start channel
        """);
    listenerCaptor.getValue().onComplete(1);

    verify(accountSyncService).syncInstanceAccounts(instance);
    verify(provisioningService).startProvisioning("inst_1");

    ArgumentCaptor<InstanceWechatBindingEntity> bindingCaptor = ArgumentCaptor.forClass(InstanceWechatBindingEntity.class);
    verify(mutationMapper, atLeastOnce()).updateWechatBinding(bindingCaptor.capture());
    assertThat(bindingCaptor.getAllValues().getLast().getStatus()).isEqualTo("connected");
    assertThat(bindingCaptor.getAllValues().getLast().getRuntimeStatus()).isEqualTo("restarting");
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
