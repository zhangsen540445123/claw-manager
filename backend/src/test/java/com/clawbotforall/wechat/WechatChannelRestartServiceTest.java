package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceQueryService;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WechatChannelRestartServiceTest {

  @Mock
  InstanceAggregateMapper aggregateMapper;

  @Mock
  InstanceMutationMapper mutationMapper;

  @Mock
  OpenClawRuntime openClawRuntime;

  @Mock
  OpenClawGatewayRpcService gatewayRpcService;

  @Mock
  WechatAccountSyncService accountSyncService;

  @Mock
  InstanceQueryService queryService;

  @Mock
  InstanceEventPublisher eventPublisher;

  WechatChannelRestartService service;

  @BeforeEach
  void setUp() {
    service = new WechatChannelRestartService(
        aggregateMapper,
        mutationMapper,
        openClawRuntime,
        gatewayRpcService,
        accountSyncService,
        queryService,
        eventPublisher
    );
  }

  @Test
  void restartAccountStartsOnlySelectedWechatAccountChannel() {
    InstanceEntity instance = instance();
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "2026-06-20T00:00:00Z"));
    when(aggregateMapper.findWechatAccountByAccountId("wx_1")).thenReturn(account("wx_1", "inst_1"));
    when(queryService.findPublicInstance("inst_1", null)).thenReturn(Optional.empty());

    service.restartAccount(instance, "wx_1");

    verify(gatewayRpcService).restartWechatChannel(instance, List.of("wx_1"));
    verify(accountSyncService).syncInstanceAccounts(instance);
    verify(mutationMapper, org.mockito.Mockito.times(2)).upsertWechatAccountChannel(any());
  }

  @Test
  void restartAccountRejectsAccountFromAnotherInstance() {
    InstanceEntity instance = instance();
    when(aggregateMapper.findWechatAccountByAccountId("wx_other")).thenReturn(account("wx_other", "inst_other"));

    assertThatThrownBy(() -> service.restartAccount(instance, "wx_other"))
        .hasMessage("微信绑定账号不存在。");
  }

  private static InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setStatus("running");
    instance.setName("实例一");
    return instance;
  }

  private static WechatPairedAccountEntity account(String accountId, String instanceId) {
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setAccountId(accountId);
    account.setInstanceId(instanceId);
    account.setPhone("13572873189");
    account.setWechatUserId("wechat-user");
    return account;
  }
}
