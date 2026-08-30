package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

  @Mock
  ObjectProvider<WechatUserCleanupService> cleanupServiceProvider;

  @Mock
  WechatUserCleanupService cleanupService;

  WechatAccountSyncService service;

  @BeforeEach
  void setUp() {
    service = new WechatAccountSyncService(
        aggregateMapper,
        mutationMapper,
        fileService,
        new WechatAccountReader(new ObjectMapper()),
        bindLinkMapper,
        cleanupServiceProvider,
        new ObjectMapper()
    );
  }

  @AfterEach
  void clearTransactionSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void writesAccountIndexWithAtomicReplacementAndRemovesTemporaryFile() throws Exception {
    Path indexPath = tempDir.resolve("openclaw-weixin").resolve("accounts.json");
    Files.createDirectories(indexPath.getParent());
    Files.writeString(indexPath, "[\"stale-account\"]");

    service.writeAccountIndexAtomically(indexPath, List.of("wx_2", "wx_1", "wx_2"));

    List<String> stored = new ObjectMapper().readValue(indexPath.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    assertThat(stored).containsExactly("wx_2", "wx_1", "wx_2");
    try (var files = Files.list(indexPath.getParent())) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.startsWith("accounts.json.") && name.endsWith(".tmp"));
    }
  }

  @Test
  void removeAccountStateFilesFailsWhenWechatStateCannotBeDeleted() throws Exception {
    Path homeDir = tempDir.resolve("broken-home");
    Path stateDir = homeDir.resolve(".openclaw").resolve("openclaw-weixin");
    Path blockedCredential = stateDir.resolve("accounts").resolve("wx_1.json");
    Files.createDirectories(blockedCredential);
    Files.writeString(blockedCredential.resolve("child"), "cannot-delete-non-empty-directory");
    InstancePaths paths = new InstancePaths(
        tempDir.resolve("base"), homeDir, tempDir.resolve("workspace"), tempDir.resolve("logs")
    );

    assertThatThrownBy(() -> service.removeAccountStateFiles(paths, "wx_1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("删除微信账号状态失败");
  }

  @Test
  void keepsProtectedRuntimeInitializingAccountWhenRawAccountHasNoPersistedBinding() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();

    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(), List.of());
    when(bindLinkMapper.listProtectedAccountIds(eq("inst_1"), anyString())).thenReturn(List.of("wx_1"));

    service.syncInstanceAccounts(instance);

    verify(mutationMapper, never()).updateWechatAccountMetadata(any());
    verify(mutationMapper, never()).ensureWechatAccountChannel(any());
  }

  @Test
  void schedulesUnprotectedGhostAccountThroughPersistentCleanupService() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();
    Path stateDir = tempDir.resolve("home").resolve(".openclaw").resolve("openclaw-weixin");
    Files.writeString(stateDir.resolve("accounts").resolve("wx_1.sync.json"), "{}");
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(), List.of());
    when(bindLinkMapper.listProtectedAccountIds(eq("inst_1"), anyString())).thenReturn(List.of());
    when(cleanupServiceProvider.getIfAvailable()).thenReturn(cleanupService);
    WechatUserCleanupOperationEntity completed = new WechatUserCleanupOperationEntity();
    completed.setOperationId("cleanup-account-only");
    completed.setStatus("completed");
    when(cleanupService.startResidue(eq(instance), any(), eq("account_sync"))).thenReturn(completed);

    service.syncInstanceAccounts(instance);

    ArgumentCaptor<WechatUserResidueEvidence> evidence = ArgumentCaptor.forClass(WechatUserResidueEvidence.class);
    verify(cleanupService).startResidue(eq(instance), evidence.capture(), eq("account_sync"));
    assertThat(evidence.getValue().accountId()).isEqualTo("wx_1");
    assertThat(evidence.getValue().agentId()).isNull();
    assertThat(evidence.getValue().evidenceTypes()).containsExactly("wechat_account_state");
    assertThat(Files.exists(stateDir.resolve("accounts").resolve("wx_1.json"))).isTrue();
    assertThat(Files.exists(stateDir.resolve("accounts").resolve("wx_1.sync.json"))).isTrue();
    assertThat(Files.readString(stateDir.resolve("accounts.json"))).contains("wx_1");
  }

  @Test
  void defersAllGhostCleanupUntilTransactionCommitUsingSingleSynchronization() throws Exception {
    InstanceEntity instance = instanceWithStateAccounts("wx_1", "wx_2");
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(), List.of());
    when(bindLinkMapper.listProtectedAccountIds(eq("inst_1"), anyString())).thenReturn(List.of());
    when(cleanupServiceProvider.getIfAvailable()).thenReturn(cleanupService);
    WechatUserCleanupOperationEntity completed = new WechatUserCleanupOperationEntity();
    completed.setOperationId("cleanup-account-only");
    completed.setStatus("completed");
    when(cleanupService.startResidue(eq(instance), any(), eq("account_sync"))).thenReturn(completed);
    TransactionSynchronizationManager.initSynchronization();

    service.syncInstanceAccounts(instance);

    verify(cleanupService, never()).startResidue(any(), any(), anyString());
    List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
    assertThat(synchronizations).hasSize(1);

    synchronizations.getFirst().afterCommit();

    ArgumentCaptor<WechatUserResidueEvidence> evidence = ArgumentCaptor.forClass(WechatUserResidueEvidence.class);
    verify(cleanupService, org.mockito.Mockito.times(2))
        .startResidue(eq(instance), evidence.capture(), eq("account_sync"));
    assertThat(evidence.getAllValues()).extracting(WechatUserResidueEvidence::accountId)
        .containsExactly("wx_1", "wx_2");
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
  void deletingAccountRedactsRelatedBindLinkAuditAndIdlesLastBinding() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();
    WechatPairedAccountEntity existing = new WechatPairedAccountEntity();
    existing.setAccountId("wx_1");
    existing.setPhone("13572873189");
    existing.setInstanceId("inst_1");
    when(aggregateMapper.findWechatAccountByAccountId("wx_1")).thenReturn(existing);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(), List.of());

    service.deleteAccount(instance, "wx_1");

    verify(mutationMapper).deleteWechatAccount("inst_1", "wx_1");
    verify(bindLinkMapper).redactByPhoneOrAccountId(eq("13572873189"), eq("wx_1"), anyString());
  }

  @Test
  void updatesPhoneAndRemarkForWechatAccount() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    WechatPairedAccountEntity existing = new WechatPairedAccountEntity();
    existing.setAccountId("wx_1");
    existing.setPhone("");
    existing.setInstanceId("inst_1");
    when(aggregateMapper.findWechatAccountByAccountId("wx_1")).thenReturn(existing);
    when(aggregateMapper.findWechatAccountByPhone("13572873189")).thenReturn(null);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(existing), List.of(existing));
    when(fileService.paths("inst_1")).thenReturn(new InstancePaths(
        tempDir,
        tempDir.resolve("home"),
        tempDir.resolve("workspace"),
        tempDir.resolve("logs")
    ));

    service.updateProfile(instance, "wx_1", "135 7287 3189", " 新备注 ");

    verify(mutationMapper).updateWechatAccountProfile(
        eq("inst_1"),
        eq("wx_1"),
        eq("13572873189"),
        eq("新备注"),
        anyString()
    );
  }

  @Test
  void clearsPhoneWhenUpdatingWechatAccountProfile() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    WechatPairedAccountEntity existing = new WechatPairedAccountEntity();
    existing.setAccountId("wx_1");
    existing.setPhone("13572873189");
    existing.setInstanceId("inst_1");
    when(aggregateMapper.findWechatAccountByAccountId("wx_1")).thenReturn(existing);
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(existing), List.of(existing));
    when(fileService.paths("inst_1")).thenReturn(new InstancePaths(
        tempDir,
        tempDir.resolve("home"),
        tempDir.resolve("workspace"),
        tempDir.resolve("logs")
    ));

    service.updateProfile(instance, "wx_1", "", "备注");

    verify(mutationMapper).updateWechatAccountProfile(
        eq("inst_1"),
        eq("wx_1"),
        isNull(),
        eq("备注"),
        anyString()
    );
  }

  @Test
  void rejectsDuplicatePhoneWhenUpdatingWechatAccountProfile() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    WechatPairedAccountEntity existing = new WechatPairedAccountEntity();
    existing.setAccountId("wx_1");
    existing.setInstanceId("inst_1");
    WechatPairedAccountEntity duplicate = new WechatPairedAccountEntity();
    duplicate.setAccountId("wx_2");
    duplicate.setPhone("13572873189");
    duplicate.setInstanceId("inst_2");
    when(aggregateMapper.findWechatAccountByAccountId("wx_1")).thenReturn(existing);
    when(aggregateMapper.findWechatAccountByPhone("13572873189")).thenReturn(duplicate);

    assertThatThrownBy(() -> service.updateProfile(instance, "wx_1", "13572873189", "备注"))
        .isInstanceOf(ApiException.class)
        .hasMessage("该手机号已绑定到其他微信用户。");
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
    verify(bindLinkMapper).redactByInstanceId(eq("inst_1"), anyString());
  }

  @Test
  void deleteAllAccountsReportsFalseWhenNoPersistedAccountExists() throws Exception {
    InstanceEntity instance = instanceWithStateAccount();
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst_1"))).thenReturn(List.of());

    assertThat(service.deleteAllAccounts(instance)).isFalse();

    verify(mutationMapper).deleteWechatAccountsForInstance("inst_1");
    verify(bindLinkMapper).redactByInstanceId(eq("inst_1"), anyString());
  }

  @Test
  void refreshesOriginalCredentialFromRejectedLoginAndClearsStaleSessionFiles() throws Exception {
    Path sourceHome = tempDir.resolve("source-home");
    Path sourceState = sourceHome.resolve(".openclaw").resolve("openclaw-weixin");
    Path sourceAccounts = sourceState.resolve("accounts");
    Files.createDirectories(sourceAccounts);
    Files.writeString(sourceState.resolve("accounts.json"), "[\"new-account\"]");
    Files.writeString(sourceAccounts.resolve("new-account.json"), "{\"token\":\"new-token\",\"userId\":\"wechat-user\"}");
    Files.writeString(sourceAccounts.resolve("new-account.sync.json"), "{\"get_updates_buf\":\"source\"}");

    Path targetHome = tempDir.resolve("target-home");
    Path targetState = targetHome.resolve(".openclaw").resolve("openclaw-weixin");
    Path targetAccounts = targetState.resolve("accounts");
    Files.createDirectories(targetAccounts);
    Files.writeString(targetState.resolve("accounts.json"), "[\"old-account\"]");
    Files.writeString(targetAccounts.resolve("old-account.json"), "{\"token\":\"old-token\",\"userId\":\"wechat-user\"}");
    Files.writeString(targetAccounts.resolve("old-account.sync.json"), "{\"get_updates_buf\":\"old\"}");
    Files.writeString(targetAccounts.resolve("old-account.context-tokens.json"), "{\"wechat-user\":\"old-context\"}");

    InstanceEntity sourceInstance = new InstanceEntity();
    sourceInstance.setId("source_inst");
    WechatPairedAccountEntity targetAccount = new WechatPairedAccountEntity();
    targetAccount.setAccountId("old-account");
    targetAccount.setInstanceId("target_inst");
    targetAccount.setWechatUserId("wechat-user");
    when(fileService.paths("source_inst")).thenReturn(new InstancePaths(
        tempDir.resolve("source"),
        sourceHome,
        tempDir.resolve("source-workspace"),
        tempDir.resolve("source-logs")
    ));
    when(fileService.paths("target_inst")).thenReturn(new InstancePaths(
        tempDir.resolve("target"),
        targetHome,
        tempDir.resolve("target-workspace"),
        tempDir.resolve("target-logs")
    ));

    assertThat(service.refreshAccountCredentialsFromRejectedLogin(sourceInstance, "new-account", targetAccount)).isTrue();

    assertThat(Files.readString(targetAccounts.resolve("old-account.json"))).contains("new-token");
    assertThat(Files.exists(targetAccounts.resolve("old-account.sync.json"))).isFalse();
    assertThat(Files.exists(targetAccounts.resolve("old-account.context-tokens.json"))).isFalse();
    assertThat(Files.readString(targetState.resolve("accounts.json"))).contains("old-account");
    assertThat(Files.exists(sourceAccounts.resolve("new-account.json"))).isTrue();
  }

  private InstanceEntity instanceWithStateAccount() throws Exception {
    return instanceWithStateAccounts("wx_1");
  }

  private InstanceEntity instanceWithStateAccounts(String... accountIds) throws Exception {
    Path homeDir = tempDir.resolve("home");
    Path stateDir = homeDir.resolve(".openclaw").resolve("openclaw-weixin");
    Path accountsDir = stateDir.resolve("accounts");
    Files.createDirectories(accountsDir);
    new ObjectMapper().writeValue(stateDir.resolve("accounts.json").toFile(), accountIds);
    for (int index = 0; index < accountIds.length; index++) {
      Files.writeString(
          accountsDir.resolve(accountIds[index] + ".json"),
          "{\"userId\":\"user-" + (char) ('a' + index) + "\"}"
      );
    }

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
