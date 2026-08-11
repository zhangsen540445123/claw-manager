package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.miniapp.MiniappUserBindingEntity;
import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.miniapp.MiniappUserKeyMapper;
import com.clawbotforall.openviking.OpenVikingUserKeyService;
import com.clawbotforall.useragent.UserAgentIdentityEntity;
import com.clawbotforall.useragent.UserAgentIdentityMapper;
import com.clawbotforall.useragent.UserAgentIdentityResult;
import com.clawbotforall.useragent.UserAgentIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class WechatUserRebindServiceTest {

  @Mock WechatRebindOperationMapper operationMapper;
  @Mock WechatBindLinkMapper linkMapper;
  @Mock InstanceAggregateMapper aggregateMapper;
  @Mock InstanceMutationMapper mutationMapper;
  @Mock UserAgentIdentityMapper identityMapper;
  @Mock UserAgentIdentityService identityService;
  @Mock MiniappUserBindingMapper miniappBindingMapper;
  @Mock MiniappUserKeyMapper miniappKeyMapper;
  @Mock OpenClawGatewayRpcService gatewayRpcService;
  @Mock OpenClawUserDataCleaner dataCleaner;
  @Mock WechatAccountSyncService accountSyncService;
  @Mock InstanceFileService fileService;
  @Mock OpenVikingUserKeyService userKeyService;
  @Mock PlatformTransactionManager transactionManager;

  WechatUserRebindService service;
  AtomicReference<WechatRebindOperationEntity> operation;
  AtomicReference<WechatBindLinkEntity> storedLink;

  @BeforeEach
  void setUp() {
    operation = new AtomicReference<>();
    storedLink = new AtomicReference<>();
    lenient().when(operationMapper.findByTokenForUpdate(any())).thenAnswer(ignored -> operation.get());
    lenient().when(operationMapper.insert(any())).thenAnswer(invocation -> {
      operation.set(invocation.getArgument(0));
      return 1;
    });
    lenient().when(operationMapper.update(any())).thenAnswer(invocation -> {
      operation.set(invocation.getArgument(0));
      return 1;
    });
    lenient().when(linkMapper.findByTokenForUpdate(any())).thenAnswer(ignored -> storedLink.get());
    lenient().when(linkMapper.findByToken(any())).thenAnswer(ignored -> storedLink.get());
    lenient().when(linkMapper.update(any())).thenAnswer(invocation -> {
      storedLink.set(invocation.getArgument(0));
      return 1;
    });
    lenient().when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-old"))
        .thenAnswer(ignored -> oldAccount());
    service = newService(transactionManager);
  }

  private WechatUserRebindService newService(PlatformTransactionManager manager) {
    return new WechatUserRebindService(
        operationMapper,
        linkMapper,
        aggregateMapper,
        mutationMapper,
        identityMapper,
        identityService,
        miniappBindingMapper,
        miniappKeyMapper,
        gatewayRpcService,
        dataCleaner,
        accountSyncService,
        fileService,
        userKeyService,
        new ObjectMapper(),
        manager
    );
  }

  @Test
  void replacesOldIdentityAndCompletesAllCleanupStages() {
    WechatBindLinkEntity link = link();
    storedLink.set(link);
    WechatPairedAccountEntity oldAccount = oldAccount();
    InstanceEntity instance = instance();
    UserAgentIdentityEntity identity = identity("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    MiniappUserBindingEntity miniapp = new MiniappUserBindingEntity();
    miniapp.setOpenidHash("openid-hash");
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of(miniapp));
    when(dataCleaner.readOldSessionIds("inst_1", identity.getAgentId())).thenReturn(List.of("session-old"));
    when(identityService.replaceForRebind(eq("inst_1"), eq("wechat-user"), eq(identity.getAgentId()), any()))
        .thenAnswer(invocation -> new UserAgentIdentityResult(invocation.getArgument(3), "wx_memory", true));
    when(gatewayRpcService.replaceUserAgent(eq(instance), any(), eq("wx_memory"), eq("account-new"),
        eq("wechat-user"), eq(identity.getAgentId()), eq(List.of("api:openid-hash"))))
        .thenReturn(new OpenClawGatewayRpcService.ReplaceUserAgentResult(true, true, true,
            List.of(identity.getAgentId()), List.of()));
    when(aggregateMapper.findWechatAccountByAccountId("account-new")).thenReturn(null);

    WechatBindLinkEntity result = service.startOrResume(link, oldAccount, instance, "account-new", "wechat-user");

    assertThat(result.getStatus()).isEqualTo("connected");
    assertThat(result.getTargetAccountId()).isNull();
    assertThat(result.getScannedWechatUserId()).isNull();
    assertThat(operation.get().getStatus()).isEqualTo("completed");
    assertThat(operation.get().getStage()).isEqualTo("completed");
    assertThat(operation.get().getNewAgentId()).matches("user_[0-9a-f]{32}");
    assertThat(operation.get().getOpenvikingUserId()).isEqualTo("wx_memory");

    InOrder order = inOrder(gatewayRpcService, miniappKeyMapper, miniappBindingMapper, identityService,
        dataCleaner, mutationMapper, userKeyService);
    order.verify(gatewayRpcService).stopWechatChannel(instance, List.of("account-old", "account-new"));
    order.verify(miniappKeyMapper).deleteByAgentId(identity.getAgentId());
    order.verify(miniappBindingMapper).deleteByAgentId(identity.getAgentId());
    order.verify(identityService).replaceForRebind(eq("inst_1"), eq("wechat-user"), eq(identity.getAgentId()), any());
    order.verify(dataCleaner).deleteOldUserData("inst_1", identity.getAgentId(), List.of("session-old"),
        List.of("api:openid-hash"));
    order.verify(mutationMapper).deleteWechatAccount("inst_1", "account-old");
    order.verify(mutationMapper).insertWechatAccount(any());
    order.verify(userKeyService).rotateUserKey("wx_memory");
    order.verify(gatewayRpcService).startWechatChannel(instance, List.of("account-new"));
    verify(accountSyncService).removeAccountStateFiles(any(), eq("account-old"));
    verify(accountSyncService).syncInstanceAccounts(instance);
  }

  @Test
  void protectsScannedAccountIdWhileCleanupIsInProgress() {
    WechatBindLinkEntity link = link();
    storedLink.set(link);
    WechatPairedAccountEntity oldAccount = oldAccount();
    InstanceEntity instance = instance();
    UserAgentIdentityEntity identity = identity("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst_1", identity.getAgentId())).thenReturn(List.of());
    org.mockito.Mockito.doThrow(new IllegalStateException("stop failed"))
        .when(gatewayRpcService).stopWechatChannel(instance, List.of("account-old", "account-new"));

    WechatBindLinkEntity result = service.startOrResume(link, oldAccount, instance, "account-new", "wechat-user");

    assertThat(result.getStatus()).isEqualTo("cleanup_failed");
    assertThat(result.getTargetAccountId()).isEqualTo("account-new");
    assertThat(operation.get().getOldAccountId()).isEqualTo("account-old");
    assertThat(operation.get().getNewAccountId()).isEqualTo("account-new");
  }

  @Test
  void routingConflictMarksCleanupFailedAndRetryReusesPersistedNewAgentId() {
    WechatBindLinkEntity link = link();
    storedLink.set(link);
    WechatPairedAccountEntity oldAccount = oldAccount();
    InstanceEntity instance = instance();
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    UserAgentIdentityEntity identity = identity("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst_1", identity.getAgentId())).thenReturn(List.of());
    when(identityService.replaceForRebind(eq("inst_1"), eq("wechat-user"), eq(identity.getAgentId()), any()))
        .thenAnswer(invocation -> new UserAgentIdentityResult(invocation.getArgument(3), "wx_memory", true));
    when(gatewayRpcService.replaceUserAgent(eq(instance), any(), eq("wx_memory"), eq("account-new"),
        eq("wechat-user"), eq(identity.getAgentId()), eq(List.of())))
        .thenReturn(new OpenClawGatewayRpcService.ReplaceUserAgentResult(false, false, false, List.of(),
            List.of(java.util.Map.of("channel", "other"))));

    WechatBindLinkEntity failed = service.startOrResume(link, oldAccount, instance, "account-new", "wechat-user");
    String generatedAgentId = operation.get().getNewAgentId();

    assertThat(failed.getStatus()).isEqualTo("cleanup_failed");
    assertThat(failed.getCleanupStage()).isEqualTo("identity_replaced");
    assertThat(failed.getCleanupError()).contains("路由冲突");
    assertThat(operation.get().getAttemptCount()).isEqualTo(1);

    WechatBindLinkEntity retried = service.retry(link.getToken());

    assertThat(retried.getStatus()).isEqualTo("cleanup_failed");
    assertThat(operation.get().getNewAgentId()).isEqualTo(generatedAgentId);
    assertThat(operation.get().getAttemptCount()).isEqualTo(2);
    verify(identityService).replaceForRebind(eq("inst_1"), eq("wechat-user"), eq(identity.getAgentId()),
        eq(generatedAgentId));
    verify(dataCleaner, never()).deleteOldUserData(any(), any(), any(), any());
  }


  @Test
  void cancelsFailedCleanupBeforeIdentityReplacementAndReleasesActiveOperation() {
    WechatBindLinkEntity link = link();
    link.setStatus("cleanup_failed");
    link.setTargetAccountId("account-new");
    link.setScannedWechatUserId("wechat-user");
    link.setQrPayload("secret-qr");
    link.setQrLink("https://qr.example.test");
    link.setMiniappOpenidHash("miniapp-sensitive");
    storedLink.set(link);

    WechatRebindOperationEntity failed = failedOperation("channels_stopped");
    operation.set(failed);
    InstanceEntity instance = instance();
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.findWechatAccountByAccountId("account-new")).thenReturn(null);
    when(fileService.paths("inst_1")).thenReturn(null);

    WechatBindLinkEntity cancelled = service.cancelFailed(link.getToken());

    assertThat(cancelled.getStatus()).isEqualTo("revoked");
    assertThat(cancelled.getTargetAccountId()).isNull();
    assertThat(cancelled.getScannedWechatUserId()).isNull();
    assertThat(cancelled.getMiniappOpenidHash()).isNull();
    assertThat(cancelled.getQrPayload()).isNull();
    assertThat(cancelled.getQrLink()).isNull();
    assertThat(operation.get().getStatus()).isEqualTo("cancelled");
    assertThat(operation.get().getCompletedAt()).isNotBlank();
    verify(accountSyncService).removeAccountStateFiles(null, "account-new");
    verify(gatewayRpcService).startWechatChannel(instance, List.of("account-old"));
  }

  @Test
  void doesNotDeleteOldAccountFilesWhenCancelledOperationReusesSameAccountId() {
    WechatBindLinkEntity link = link();
    link.setStatus("cleanup_failed");
    storedLink.set(link);

    WechatRebindOperationEntity failed = failedOperation("channels_stopped");
    failed.setNewAccountId("account-old");
    operation.set(failed);
    InstanceEntity instance = instance();
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.findWechatAccountByAccountId("account-old")).thenReturn(null);

    WechatBindLinkEntity cancelled = service.cancelFailed(link.getToken());

    assertThat(cancelled.getStatus()).isEqualTo("revoked");
    verify(accountSyncService, never()).removeAccountStateFiles(any(), any());
    verify(gatewayRpcService).startWechatChannel(instance, List.of("account-old"));
  }

  @Test
  void refusesToCancelFailedCleanupAfterIdentityWasReplaced() {
    WechatBindLinkEntity link = link();
    link.setStatus("cleanup_failed");
    storedLink.set(link);
    operation.set(failedOperation("identity_replaced"));

    assertThatThrownBy(() -> service.cancelFailed(link.getToken()))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("不可逆");

    verify(accountSyncService, never()).removeAccountStateFiles(any(), any());
  }

  @Test
  void serializesRebindCaptureByLockingPairedAccountBeforeIdentityAndActiveOperation() {
    WechatBindLinkEntity link = link();
    storedLink.set(link);
    WechatPairedAccountEntity oldAccount = oldAccount();
    InstanceEntity instance = instance();
    UserAgentIdentityEntity identity = identity("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-old")).thenReturn(oldAccount);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst_1", identity.getAgentId())).thenReturn(List.of());
    org.mockito.Mockito.doThrow(new IllegalStateException("stop failed"))
        .when(gatewayRpcService).stopWechatChannel(instance, List.of("account-old", "account-new"));

    service.startOrResume(link, oldAccount, instance, "account-new", "wechat-user");

    InOrder lockOrder = inOrder(aggregateMapper, identityMapper, operationMapper);
    lockOrder.verify(aggregateMapper).findWechatAccountByAccountIdForUpdate("account-old");
    lockOrder.verify(identityMapper).findByWechatUserIdForUpdate("wechat-user");
    lockOrder.verify(operationMapper).findActiveForUserForUpdate("13572873189", "wechat-user");
  }

  @Test
  void mapsConcurrentActiveOperationUniqueConstraintToConflict() {
    WechatBindLinkEntity link = link();
    storedLink.set(link);
    WechatPairedAccountEntity oldAccount = oldAccount();
    InstanceEntity instance = instance();
    UserAgentIdentityEntity identity = identity("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-old")).thenReturn(oldAccount);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst_1", identity.getAgentId())).thenReturn(List.of());
    when(operationMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate active phone"));

    assertThatThrownBy(() -> service.startOrResume(link, oldAccount, instance, "account-new", "wechat-user"))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("已有进行中的重新绑定");
  }

  @Test
  void migratesWechatAccountRecordsInsideOneTransaction() {
    RecordingTransactionManager recordingTransactions = new RecordingTransactionManager();
    service = newService(recordingTransactions);
    WechatBindLinkEntity link = link();
    storedLink.set(link);
    WechatPairedAccountEntity oldAccount = oldAccount();
    InstanceEntity instance = instance();
    UserAgentIdentityEntity identity = identity("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst_1", identity.getAgentId())).thenReturn(List.of());
    when(identityService.replaceForRebind(eq("inst_1"), eq("wechat-user"), eq(identity.getAgentId()), any()))
        .thenAnswer(invocation -> new UserAgentIdentityResult(invocation.getArgument(3), "wx_memory", true));
    when(gatewayRpcService.replaceUserAgent(eq(instance), any(), eq("wx_memory"), eq("account-new"),
        eq("wechat-user"), eq(identity.getAgentId()), eq(List.of())))
        .thenReturn(new OpenClawGatewayRpcService.ReplaceUserAgentResult(true, true, true,
            List.of(identity.getAgentId()), List.of()));
    when(aggregateMapper.findWechatAccountByAccountId("account-new")).thenReturn(null);
    when(mutationMapper.deleteWechatAccount("inst_1", "account-old")).thenAnswer(invocation -> {
      assertThat(recordingTransactions.isActive()).isTrue();
      return 1;
    });
    when(mutationMapper.insertWechatAccount(any())).thenAnswer(invocation -> {
      assertThat(recordingTransactions.isActive()).isTrue();
      return 1;
    });
    when(mutationMapper.ensureWechatAccountChannel(any())).thenAnswer(invocation -> {
      assertThat(recordingTransactions.isActive()).isTrue();
      return 1;
    });

    WechatBindLinkEntity result = service.startOrResume(link, oldAccount, instance, "account-new", "wechat-user");

    assertThat(result.getStatus()).isEqualTo("connected");
    assertThat(recordingTransactions.isActive()).isFalse();
  }

  private static WechatRebindOperationEntity failedOperation(String stage) {
    WechatRebindOperationEntity operation = new WechatRebindOperationEntity();
    operation.setBindToken("bind-token");
    operation.setPhone("13572873189");
    operation.setWechatUserId("wechat-user");
    operation.setOldInstanceId("inst_1");
    operation.setOldAccountId("account-old");
    operation.setNewInstanceId("inst_1");
    operation.setNewAccountId("account-new");
    operation.setOldAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    operation.setNewAgentId("user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    operation.setOpenvikingUserId("wx_memory");
    operation.setStatus("cleanup_failed");
    operation.setStage(stage);
    operation.setAttemptCount(1);
    operation.setCreatedAt("2026-08-09T00:00:00Z");
    operation.setUpdatedAt("2026-08-09T00:00:00Z");
    return operation;
  }

  private static WechatBindLinkEntity link() {
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setToken("bind-token");
    link.setMode("existing");
    link.setPhone("13572873189");
    link.setInstanceId("inst_1");
    link.setTargetAccountId("account-old");
    link.setStatus("scanned");
    link.setQrPayload("secret-qr");
    link.setQrLink("https://qr.example.test");
    link.setQrMode("link");
    link.setMiniappOpenidHash("miniapp-sensitive");
    link.setCreatedAt("2026-08-09T00:00:00Z");
    link.setUpdatedAt("2026-08-09T00:00:00Z");
    return link;
  }

  private static WechatPairedAccountEntity oldAccount() {
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setAccountId("account-old");
    account.setPhone("13572873189");
    account.setInstanceId("inst_1");
    account.setWechatUserId("wechat-user");
    account.setRemark("老用户");
    account.setBaseUrl("https://wechat.example.test");
    account.setSavedAt("2026-08-01T00:00:00Z");
    account.setBoundAt("2026-07-01T00:00:00Z");
    account.setUpdatedAt("2026-08-01T00:00:00Z");
    return account;
  }

  private static InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("实例一");
    instance.setStatus("running");
    return instance;
  }

  private static UserAgentIdentityEntity identity(String agentId) {
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId(agentId);
    identity.setWechatUserId("wechat-user");
    identity.setOpenvikingUserId("wx_memory");
    return identity;
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final AtomicBoolean active = new AtomicBoolean();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      if (!active.compareAndSet(false, true)) {
        throw new IllegalStateException("nested transaction not expected");
      }
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      active.set(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      active.set(false);
    }

    boolean isActive() {
      return active.get();
    }
  }

}
