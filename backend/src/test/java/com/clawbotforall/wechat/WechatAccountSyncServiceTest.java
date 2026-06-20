package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.WechatAccountChannelEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WechatAccountSyncServiceTest {

  @TempDir
  Path tempDir;

  @Mock
  InstanceAggregateMapper aggregateMapper;

  @Mock
  InstanceMutationMapper mutationMapper;

  @Mock
  InstanceFileService fileService;

  @Mock
  WechatBindLinkMapper bindLinkMapper;

  WechatAccountSyncService service;

  @BeforeEach
  void setUp() {
    service = new WechatAccountSyncService(
        aggregateMapper,
        mutationMapper,
        fileService,
        new WechatAccountReader(new ObjectMapper()),
        bindLinkMapper,
        new ObjectMapper()
    );
  }

  @Test
  void keepsRuntimeInitializingWhenRawAccountHasNoPersistedBinding() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();

    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(), List.of());

    service.syncInstanceAccounts(instance);

    verify(mutationMapper, never()).updateWechatAccountMetadata(any());
    verify(mutationMapper, never()).ensureWechatAccountChannel(any());
  }

  @Test
  void updatesPersistedBindingMetadataAndEnsuresAccountChannel() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();
    WechatPairedAccountEntity existing = new WechatPairedAccountEntity();
    existing.setAccountId("wx_1");
    existing.setPhone("13572873189");
    existing.setInstanceId("inst_1");
    existing.setWechatUserId("");
    existing.setRemark("备注");
    existing.setBaseUrl("");

    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(existing), List.of(existing));

    service.syncInstanceAccounts(instance);

    ArgumentCaptor<WechatPairedAccountEntity> accountCaptor = ArgumentCaptor.forClass(WechatPairedAccountEntity.class);
    verify(mutationMapper).updateWechatAccountMetadata(accountCaptor.capture());
    WechatPairedAccountEntity account = accountCaptor.getValue();
    assertThat(account.getAccountId()).isEqualTo("wx_1");
    assertThat(account.getWechatUserId()).isEqualTo("user-a");
    assertThat(account.getRemark()).isEqualTo("备注");
    assertThat(account.getUpdatedAt()).isNotBlank();

    ArgumentCaptor<WechatAccountChannelEntity> channelCaptor = ArgumentCaptor.forClass(WechatAccountChannelEntity.class);
    verify(mutationMapper).ensureWechatAccountChannel(channelCaptor.capture());
    WechatAccountChannelEntity channel = channelCaptor.getValue();
    assertThat(channel.getAccountId()).isEqualTo("wx_1");
    assertThat(channel.getWechatUserId()).isEqualTo("user-a");
    assertThat(channel.getStatus()).isEqualTo("unknown");
  }

  @Test
  void deletingAccountRemovesRelatedBindLinksAndIdlesLastBinding() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();
    WechatPairedAccountEntity existing = new WechatPairedAccountEntity();
    existing.setAccountId("wx_1");
    existing.setPhone("13572873189");
    existing.setInstanceId("inst_1");
    when(aggregateMapper.findWechatAccountByAccountId("wx_1")).thenReturn(existing);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(), List.of());

    service.deleteAccount(instance, "wx_1");

    verify(mutationMapper).deleteWechatAccount("inst_1", "wx_1");
    verify(bindLinkMapper).deleteByPhoneOrAccountId("13572873189", "wx_1");
  }

  @Test
  void deleteAllAccountsReportsWhetherPersistedAccountsExisted() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();
    WechatPairedAccountEntity existing = new WechatPairedAccountEntity();
    existing.setAccountId("wx_1");
    existing.setInstanceId("inst_1");
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(existing));

    assertThat(service.deleteAllAccounts(instance)).isTrue();

    verify(mutationMapper).deleteWechatAccountsForInstance("inst_1");
    verify(bindLinkMapper).deleteByInstanceId("inst_1");
  }

  @Test
  void deleteAllAccountsReportsFalseWhenNoPersistedAccountExists() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of());

    assertThat(service.deleteAllAccounts(instance)).isFalse();

    verify(mutationMapper).deleteWechatAccountsForInstance("inst_1");
    verify(bindLinkMapper).deleteByInstanceId("inst_1");
  }

  private InstanceEntity instanceWithStateAccount() throws Exception {
    Path homeDir = tempDir.resolve("home");
    Path stateDir = homeDir.resolve(".openclaw").resolve("openclaw-weixin");
    Path accountsDir = stateDir.resolve("accounts");
    Files.createDirectories(accountsDir);
    Files.writeString(stateDir.resolve("accounts.json"), "[\"wx_1\"]");
    Files.writeString(accountsDir.resolve("wx_1.json"), "{\"userId\":\"user-a\"}");

    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    when(fileService.paths("inst_1")).thenReturn(new InstancePaths(
        tempDir,
        homeDir,
        tempDir.resolve("workspace"),
        tempDir.resolve("logs")
    ));
    return instance;
  }

}
