package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.WechatAccountChannelEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.useragent.UserAgentIdentityEntity;
import com.clawbotforall.useragent.UserAgentIdentityMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WechatUserQueryServiceTest {
  @Mock InstanceAggregateMapper aggregateMapper;
  @Mock UserAgentIdentityMapper identityMapper;
  @Mock MiniappUserBindingMapper miniappBindingMapper;
  @Mock WechatUserCleanupOperationMapper cleanupMapper;

  @Test
  void mergesActiveAccountWithCleanupStateAndIncludesFailedGhostOperation() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst-1");
    instance.setName("实例一");
    instance.setStatus("running");
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setInstanceId("inst-1");
    account.setAccountId("account-1");
    account.setPhone("13500000000");
    account.setWechatUserId("wechat-1");
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    identity.setOpenvikingUserId("wx-memory");
    WechatAccountChannelEntity channel = new WechatAccountChannelEntity();
    channel.setInstanceId("inst-1");
    channel.setAccountId("account-1");
    channel.setStatus("ready");
    channel.setMessage("channel ready");
    channel.setUpdatedAt("2026-08-11T01:02:03Z");
    channel.setLastStartedAt("2026-08-11T01:00:00Z");
    WechatUserCleanupOperationEntity cleaning = operation("op-cleaning", "inst-1", "account-1", "cleaning");
    cleaning.setStage("routing_deleted");
    WechatUserCleanupOperationEntity ghost = operation("op-ghost", "inst-1", "ghost-account", "cleanup_failed");
    ghost.setStage("wechat_files_deleted");
    ghost.setLastError("清理失败");
    ghost.setWechatUserId("ghost-peer");

    when(aggregateMapper.listAll()).thenReturn(List.of(instance));
    when(aggregateMapper.listAllWechatAccounts()).thenReturn(List.of(account));
    when(aggregateMapper.listWechatAccountChannelsByInstanceIds(List.of("inst-1"))).thenReturn(List.of(channel));
    when(identityMapper.findByWechatUserId("wechat-1")).thenReturn(identity);
    when(cleanupMapper.listActive()).thenReturn(List.of(cleaning, ghost));

    List<PublicWechatUser> users = new WechatUserQueryService(
        aggregateMapper, identityMapper, miniappBindingMapper, cleanupMapper).listUsers();

    assertThat(users).hasSize(2);
    assertThat(users.get(0).recordState()).isEqualTo("cleaning");
    assertThat(users.get(0).cleanupOperationId()).isEqualTo("op-cleaning");
    assertThat(users.get(0).channelStatus()).isEqualTo("ready");
    assertThat(users.get(0).channelMessage()).isEqualTo("channel ready");
    assertThat(users.get(0).lastStartedAt()).isEqualTo("2026-08-11T01:00:00Z");
    assertThat(users.get(1).recordState()).isEqualTo("cleanup_failed");
    assertThat(users.get(1).retryable()).isTrue();
    assertThat(users.get(1).residueTypes()).contains("cleanup_operation");
  }

  @Test
  void exposesPendingAccountCleanupAsCleaning() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst-1");
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setInstanceId("inst-1");
    account.setAccountId("account-1");
    account.setWechatUserId("wechat-1");
    WechatUserCleanupOperationEntity pending = operation("op-pending", "inst-1", "account-1", "pending");

    when(aggregateMapper.listAll()).thenReturn(List.of(instance));
    when(aggregateMapper.listAllWechatAccounts()).thenReturn(List.of(account));
    when(aggregateMapper.listWechatAccountChannelsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(identityMapper.findByWechatUserId("wechat-1")).thenReturn(null);
    when(cleanupMapper.listActive()).thenReturn(List.of(pending));

    List<PublicWechatUser> users = new WechatUserQueryService(
        aggregateMapper, identityMapper, miniappBindingMapper, cleanupMapper).listUsers();

    assertThat(users).singleElement().extracting(PublicWechatUser::recordState).isEqualTo("cleaning");
  }

  @Test
  void exposesPendingGhostCleanupAsCleaning() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst-1");
    WechatUserCleanupOperationEntity pending = operation("op-pending", "inst-1", "ghost-account", "pending");

    when(aggregateMapper.listAll()).thenReturn(List.of(instance));
    when(aggregateMapper.listAllWechatAccounts()).thenReturn(List.of());
    when(aggregateMapper.listWechatAccountChannelsByInstanceIds(List.of("inst-1"))).thenReturn(List.of());
    when(cleanupMapper.listActive()).thenReturn(List.of(pending));

    List<PublicWechatUser> users = new WechatUserQueryService(
        aggregateMapper, identityMapper, miniappBindingMapper, cleanupMapper).listUsers();

    assertThat(users).singleElement().extracting(PublicWechatUser::recordState).isEqualTo("cleaning");
  }

  private static WechatUserCleanupOperationEntity operation(
      String id, String instanceId, String accountId, String status) {
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId(id);
    operation.setInstanceId(instanceId);
    operation.setAccountId(accountId);
    operation.setStatus(status);
    operation.setStage("validated");
    return operation;
  }
}
