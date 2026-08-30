package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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
import com.clawbotforall.openviking.OpenVikingUserKeyMapper;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.trace.IntegrationTraceMapper;
import com.clawbotforall.useragent.UserAgentIdentityEntity;
import com.clawbotforall.useragent.UserAgentIdentityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class WechatUserCleanupServiceTest {
  @Mock WechatUserCleanupOperationMapper operationMapper;
  @Mock InstanceAggregateMapper aggregateMapper;
  @Mock InstanceMutationMapper mutationMapper;
  @Mock UserAgentIdentityMapper identityMapper;
  @Mock MiniappUserBindingMapper miniappBindingMapper;
  @Mock MiniappUserKeyMapper miniappKeyMapper;
  @Mock OpenVikingUserKeyMapper openVikingUserKeyMapper;
  @Mock OpenClawGatewayRpcService gatewayRpcService;
  @Mock OpenClawUserDataCleaner dataCleaner;
  @Mock WechatAccountSyncService accountSyncService;
  @Mock WechatBindLinkMapper bindLinkMapper;
  @Mock WechatRebindOperationMapper rebindOperationMapper;
  @Mock IntegrationTraceMapper traceMapper;
  @Mock OpenClawRuntime openClawRuntime;
  @Mock InstanceFileService fileService;
  @TempDir Path temp;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicReference<WechatUserCleanupOperationEntity> stored = new AtomicReference<>();
  private WechatUserCleanupService service;
  private ExecutorService cleanupExecutor;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(fileService.paths("inst-1")).thenReturn(
        new InstancePaths(temp, temp.resolve("home"), temp.resolve("workspace"), temp.resolve("logs")));
    org.mockito.Mockito.lenient().when(operationMapper.insert(any())).thenAnswer(invocation -> {
      stored.set(invocation.getArgument(0));
      return 1;
    });
    org.mockito.Mockito.lenient().when(operationMapper.update(any())).thenAnswer(invocation -> {
      stored.set(invocation.getArgument(0));
      return 1;
    });
    org.mockito.Mockito.lenient().when(operationMapper.findById(anyString())).thenAnswer(invocation -> stored.get());
    org.mockito.Mockito.lenient().when(openClawRuntime.inspectInstance(any()))
        .thenReturn(new RuntimeState(false, "stopped", "now"));
    cleanupExecutor = new DirectExecutorService();
    service = createService(cleanupExecutor);
  }

  private WechatUserCleanupService createService(ExecutorService executor) {
    return new WechatUserCleanupService(
        operationMapper, aggregateMapper, mutationMapper, identityMapper,
        miniappBindingMapper, miniappKeyMapper, openVikingUserKeyMapper,
        gatewayRpcService, dataCleaner, accountSyncService, bindLinkMapper,
        rebindOperationMapper, traceMapper, openClawRuntime, fileService, objectMapper, new RecordingTransactionManager(), executor
    );
  }

  @Test
  void startReturnsBeforeCleanupRunnableRuns() {
    ManualExecutorService manual = new ManualExecutorService();
    service = createService(manual);
    InstanceEntity instance = instance();
    configureSuccessfulCleanup(instance);

    WechatUserCleanupOperationEntity accepted = service.start(instance, "account-1", "user_center");

    assertThat(accepted.getStatus()).isEqualTo("cleaning");
    assertThat(accepted.getStage()).isEqualTo("validated");
    assertThat(manual.pendingTasks()).isEqualTo(1);
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());

    manual.runNext();

    assertThat(stored.get().getStatus()).isEqualTo("completed");
    verify(gatewayRpcService).stopWechatChannel(instance, List.of("account-1"));
  }

  @Test
  void resumeDoesNotQueueSameOperationTwice() {
    ManualExecutorService manual = new ManualExecutorService();
    service = createService(manual);
    WechatUserCleanupOperationEntity operation = activeOperation("cleaning", "validated");
    stored.set(operation);
    when(operationMapper.findByIdForUpdate(operation.getOperationId())).thenReturn(operation);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance());

    service.resume(operation.getOperationId());
    service.resume(operation.getOperationId());

    assertThat(manual.pendingTasks()).isEqualTo(1);
    assertThat(operation.getAttemptCount()).isEqualTo(2);
  }

  @Test
  void queuedCleanupDoesNotExecuteAfterOperationBecomesFailed() {
    ManualExecutorService manual = new ManualExecutorService();
    service = createService(manual);
    WechatUserCleanupOperationEntity operation = activeOperation("cleaning", "validated");
    stored.set(operation);
    when(operationMapper.findByIdForUpdate(operation.getOperationId())).thenReturn(operation);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance());

    service.resume(operation.getOperationId());
    operation.setStatus("cleanup_failed");
    manual.runNext();

    assertThat(operation.getStatus()).isEqualTo("cleanup_failed");
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
    verify(gatewayRpcService, never()).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void marksOperationFailedWhenCleanupQueueRejectsTask() {
    service = createService(new RejectingExecutorService());
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    UserAgentIdentityEntity identity = identity();
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of());

    WechatUserCleanupOperationEntity rejected = service.start(instance, "account-1", "user_center");

    assertThat(rejected.getStatus()).isEqualTo("cleanup_failed");
    assertThat(rejected.getLastError()).contains("繁忙");
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
  }

  @Test
  void removesAllLocalUserDataInSafeOrderWithoutDeletingRemoteOpenVikingMemory() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    UserAgentIdentityEntity identity = identity();
    MiniappUserBindingEntity miniapp = new MiniappUserBindingEntity();
    miniapp.setOpenidHash("openid-hash");
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of(miniapp));
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of("session-1"));
    when(gatewayRpcService.deleteUserAgent(instance, identity.getAgentId(), List.of("account-1"),
        List.of("wechat-user"), List.of("api:openid-hash"), List.of()))
        .thenReturn(new OpenClawGatewayRpcService.DeleteUserAgentResult(
            true, true, true, List.of(java.util.Map.of("peerId", "wechat-user")), List.of()));
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));
    WechatPairedAccountEntity remaining = account();
    remaining.setAccountId("account-remaining");
    remaining.setWechatUserId("wechat-user-remaining");
    when(accountSyncService.syncInstanceAccounts(instance)).thenReturn(List.of(remaining));

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result.getStatus()).isEqualTo("completed");
    assertThat(result.getStage()).isEqualTo("completed");
    assertThat(result.getPhone()).isNull();
    assertThat(result.getWechatUserId()).isNull();
    assertThat(result.getAccountId()).isNull();
    assertThat(result.getAgentId()).isNull();
    assertThat(result.getOpenvikingUserId()).isNull();
    assertThat(result.getOldSessionIdsJson()).isNull();
    assertThat(result.getApiPeerIdsJson()).isNull();
    assertThat(result.getDeletedFiles()).isEqualTo(2);
    verify(gatewayRpcService).stopWechatChannel(instance, List.of("account-1"));
    verify(dataCleaner).deleteOldUserData("inst-1", identity.getAgentId(), List.of("session-1"), List.of("api:openid-hash"));
    verify(accountSyncService).removeAccountStateFiles(fileService.paths("inst-1"), "account-1");
    InOrder databaseOrder = inOrder(miniappKeyMapper, miniappBindingMapper, openVikingUserKeyMapper,
        identityMapper, mutationMapper);
    databaseOrder.verify(miniappKeyMapper).deleteByAgentId(identity.getAgentId());
    databaseOrder.verify(miniappBindingMapper).deleteByAgentId(identity.getAgentId());
    databaseOrder.verify(openVikingUserKeyMapper).deleteByOpenvikingUserId("wx-memory");
    databaseOrder.verify(identityMapper).deleteByAgentId(identity.getAgentId());
    databaseOrder.verify(mutationMapper).deleteWechatAccount("inst-1", "account-1");
    verify(traceMapper).deleteByIdentityEvidence("inst-1", List.of("openid-hash"), List.of("session-1"));
    verify(bindLinkMapper).redactByPhoneOrAccountId(eq("13500000000"), eq("account-1"), anyString());
    verify(rebindOperationMapper).redactForCleanup(
        eq("13500000000"), eq("wechat-user"), eq("account-1"), eq(identity.getAgentId()), anyString());
    verify(accountSyncService).syncInstanceAccounts(instance);
    verify(gatewayRpcService).startWechatChannel(instance, List.of("account-remaining"));
  }

  @Test
  void doesNotStartWechatChannelsWhenInstanceWasStoppedBeforeCleanup() {
    InstanceEntity instance = instance();
    configureSuccessfulCleanup(instance);

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(accountSyncService, never()).syncInstanceAccounts(any());
    verify(gatewayRpcService, never()).startWechatChannel(any(), any());
  }

  @Test
  void doesNotStartDefaultWechatProviderWhenNoValidAccountRemains() {
    InstanceEntity instance = instance();
    configureSuccessfulCleanup(instance);
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));
    when(accountSyncService.syncInstanceAccounts(instance)).thenReturn(List.of());

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(accountSyncService).syncInstanceAccounts(instance);
    verify(gatewayRpcService, never()).startWechatChannel(any(), any());
  }

  @Test
  void retriesOnlyGatewayRecoveryWhenRemainingWechatChannelCannotRestart() {
    InstanceEntity instance = instance();
    configureSuccessfulCleanup(instance);
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));
    WechatPairedAccountEntity remaining = account();
    remaining.setAccountId("account-remaining");
    remaining.setWechatUserId("wechat-user-remaining");
    when(accountSyncService.syncInstanceAccounts(instance)).thenReturn(List.of(remaining));
    doThrow(new IllegalStateException("restart token=secret-value failed"))
        .doNothing()
        .when(gatewayRpcService).startWechatChannel(instance, List.of("account-remaining"));

    WechatUserCleanupOperationEntity failed = service.start(instance, "account-1", "user_center");

    assertThat(failed.getStatus()).isEqualTo("cleanup_failed");
    assertThat(failed.getStage()).isEqualTo("history_redacted");
    assertThat(failed.getLastError()).doesNotContain("secret-value");

    when(operationMapper.findByIdForUpdate(failed.getOperationId())).thenReturn(failed);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance);
    WechatUserCleanupOperationEntity completed = service.retry(failed.getOperationId());

    assertThat(completed.getStatus()).isEqualTo("completed");
    verify(gatewayRpcService, times(1)).stopWechatChannel(any(), any());
    verify(gatewayRpcService, times(1)).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
    verify(dataCleaner, times(1)).deleteOldUserData(anyString(), anyString(), any(), any());
    verify(accountSyncService, times(1)).removeAccountStateFiles(any(), eq("account-1"));
    verify(identityMapper, times(1)).deleteByAgentId(identity().getAgentId());
    verify(accountSyncService, times(2)).syncInstanceAccounts(instance);
    verify(gatewayRpcService, times(2)).startWechatChannel(instance, List.of("account-remaining"));
  }

  @Test
  void oldCleanupSnapshotWithoutRunningStateDoesNotStartWechatProvider() {
    InstanceEntity instance = instance();
    WechatUserCleanupOperationEntity interrupted = activeOperation("cleaning", "history_redacted");
    interrupted.setSnapshotJson("{\"instanceId\":\"inst-1\"}");
    when(operationMapper.findByIdForUpdate("cleanup-existing")).thenReturn(interrupted);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance);

    WechatUserCleanupOperationEntity result = service.resume("cleanup-existing");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(accountSyncService, never()).syncInstanceAccounts(any());
    verify(gatewayRpcService, never()).startWechatChannel(any(), any());
  }

  @Test
  void retriesFromLastSuccessfulStageWhenWechatFileDeletionPreviouslyFailed() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    UserAgentIdentityEntity identity = identity();
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of());
    when(gatewayRpcService.deleteUserAgent(instance, identity.getAgentId(), List.of("account-1"),
        List.of("wechat-user"), List.of(), List.of()))
        .thenReturn(new OpenClawGatewayRpcService.DeleteUserAgentResult(
            true, true, true, List.of(), List.of()));
    doThrow(new IllegalStateException("delete token=secret-value failed"))
        .doNothing()
        .when(accountSyncService).removeAccountStateFiles(any(), eq("account-1"));

    WechatUserCleanupOperationEntity failed = service.start(instance, "account-1", "user_center");
    assertThat(failed.getStatus()).isEqualTo("cleanup_failed");
    assertThat(failed.getStage()).isEqualTo("local_agent_data_deleted");
    assertThat(failed.getLastError()).doesNotContain("secret-value");
    verify(identityMapper, never()).deleteByAgentId(anyString());

    when(operationMapper.findByIdForUpdate(failed.getOperationId())).thenReturn(failed);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance);
    WechatUserCleanupOperationEntity completed = service.retry(failed.getOperationId());

    assertThat(completed.getStatus()).isEqualTo("completed");
    verify(gatewayRpcService, times(1)).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
    verify(dataCleaner, times(1)).deleteOldUserData(anyString(), anyString(), any(), any());
    verify(accountSyncService, times(2)).removeAccountStateFiles(any(), eq("account-1"));
    verify(identityMapper).deleteByAgentId(identity.getAgentId());
  }
  @Test
  void forwardsProtectedAgentIdsWhenCleaningHistoricalDuplicateRoute() {
    InstanceEntity instance = instance();
    String oldAgent = "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    String currentAgent = "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    WechatUserResidueEvidence evidence = new WechatUserResidueEvidence(
        "old-account", "wechat-user", oldAgent, "wx-memory",
        List.of(), List.of(), List.of(currentAgent),
        List.of("binding_agent_peer")
    );
    when(gatewayRpcService.deleteUserAgent(instance, oldAgent, List.of("old-account"),
        List.of("wechat-user"), List.of(), List.of(currentAgent)))
        .thenReturn(new OpenClawGatewayRpcService.DeleteUserAgentResult(
            true, true, true, List.of(), List.of()));

    WechatUserCleanupOperationEntity result = service.startResidue(instance, evidence, "residue_scanner");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(gatewayRpcService).deleteUserAgent(instance, oldAgent, List.of("old-account"),
        List.of("wechat-user"), List.of(), List.of(currentAgent));
    assertThat(result.getProtectedAgentIdsJson()).isNull();
  }

  @Test
  void cleansStronglyAttributedResidueWhenPairedAccountWasAlreadyDeleted() {
    InstanceEntity instance = instance();
    UserAgentIdentityEntity identity = identity();
    WechatUserResidueEvidence evidence = new WechatUserResidueEvidence(
        "account-ghost", "wechat-user", identity.getAgentId(), identity.getOpenvikingUserId(),
        List.of("api:openid-hash"), List.of("session-ghost"),
        List.of("identity_wechat_binding")
    );
    when(gatewayRpcService.deleteUserAgent(instance, identity.getAgentId(), List.of("account-ghost"),
        List.of("wechat-user"), List.of("api:openid-hash"), List.of()))
        .thenReturn(new OpenClawGatewayRpcService.DeleteUserAgentResult(
            true, true, true, List.of(java.util.Map.of("peerId", "wechat-user")), List.of()));

    WechatUserCleanupOperationEntity result = service.startResidue(instance, evidence, "residue_scanner");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(dataCleaner).deleteOldUserData("inst-1", identity.getAgentId(),
        List.of("session-ghost"), List.of("api:openid-hash"));
    verify(accountSyncService).removeAccountStateFiles(any(), eq("account-ghost"));
    verify(identityMapper).deleteByAgentId(identity.getAgentId());
    verify(mutationMapper).deleteWechatAccount("inst-1", "account-ghost");
  }

  @Test
  void cleansAccountOnlyWechatStateThroughPersistentCleanupOperation() {
    InstanceEntity instance = instance();
    WechatUserResidueEvidence evidence = new WechatUserResidueEvidence(
        "account-only-ghost", "wechat-user", null, null,
        List.of(), List.of(), List.of("wechat_account_state")
    );
    when(bindLinkMapper.listProtectedAccountIds(eq("inst-1"), anyString())).thenReturn(List.of());

    WechatUserCleanupOperationEntity result = service.startResidue(instance, evidence, "account_sync");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(gatewayRpcService).stopWechatChannel(instance, List.of("account-only-ghost"));
    verify(gatewayRpcService, never()).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
    verify(dataCleaner, never()).deleteOldUserData(anyString(), anyString(), any(), any());
    verify(accountSyncService).removeAccountStateFiles(any(), eq("account-only-ghost"));
    verify(mutationMapper).deleteWechatAccount("inst-1", "account-only-ghost");
    verify(identityMapper, never()).deleteByAgentId(anyString());
  }

  @Test
  void rejectsBareAgentDirectoryAsResidueEvidence() {
    WechatUserResidueEvidence evidence = new WechatUserResidueEvidence(
        null, null, "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null,
        List.of(), List.of(), List.of("bare_agent_directory")
    );

    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> service.startResidue(instance(), evidence, "residue_scanner"))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("无法确认幽灵 Agent");

    verify(operationMapper, never()).insert(any());
  }

  @Test
  void infersMissingIdentityFromUniqueWechatBindingAndStillDeletesAgentData() throws Exception {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]},
          "bindings": [{
            "agentId": "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "match": {"channel": "openclaw-weixin", "accountId": "account-1",
              "peer": {"kind": "direct", "id": "wechat-user"}}
          }]
        }
        """);
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(null);
    when(dataCleaner.readOldSessionIds("inst-1", "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        .thenReturn(List.of("session-orphan"));
    when(gatewayRpcService.deleteUserAgent(instance, "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        List.of("account-1"), List.of("wechat-user"), List.of(), List.of()))
        .thenReturn(new OpenClawGatewayRpcService.DeleteUserAgentResult(
            true, true, true, List.of(java.util.Map.of("peerId", "wechat-user")), List.of()));

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(gatewayRpcService).deleteUserAgent(instance, "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        List.of("account-1"), List.of("wechat-user"), List.of(), List.of());
    verify(dataCleaner).deleteOldUserData("inst-1", "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        List.of("session-orphan"), List.of());
    verify(identityMapper).deleteByAgentId("user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    verify(mutationMapper).deleteWechatAccount("inst-1", "account-1");
  }

  @Test
  void restoresRemainingWechatChannelAfterInferringAgentForRunningInstance() throws Exception {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    WechatPairedAccountEntity remainingAccount = new WechatPairedAccountEntity();
    remainingAccount.setInstanceId("inst-1");
    remainingAccount.setAccountId("account-remaining");
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [{"id": "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]},
          "bindings": [{
            "agentId": "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "match": {"channel": "openclaw-weixin", "accountId": "account-1",
              "peer": {"kind": "direct", "id": "wechat-user"}}
          }]
        }
        """);
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(openClawRuntime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(null);
    when(dataCleaner.readOldSessionIds("inst-1", "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        .thenReturn(List.of("session-orphan"));
    when(gatewayRpcService.deleteUserAgent(instance, "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        List.of("account-1"), List.of("wechat-user"), List.of(), List.of()))
        .thenReturn(new OpenClawGatewayRpcService.DeleteUserAgentResult(
            true, true, true, List.of(java.util.Map.of("peerId", "wechat-user")), List.of()));
    when(accountSyncService.syncInstanceAccounts(instance)).thenReturn(List.of(remainingAccount));

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(accountSyncService).syncInstanceAccounts(instance);
    verify(gatewayRpcService).startWechatChannel(instance, List.of("account-remaining"));
  }

  @Test
  void recordsCleanupFailureWhenMissingIdentityMatchesMultipleAgents() throws Exception {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    InstancePaths paths = paths();
    Files.writeString(paths.homeDir().resolve("openclaw.json"), """
        {
          "agents": {"list": [
            {"id": "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
            {"id": "user_cccccccccccccccccccccccccccccccc"}
          ]},
          "bindings": [
            {"agentId": "user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "match": {"channel": "openclaw-weixin", "accountId": "account-1",
                "peer": {"kind": "direct", "id": "wechat-user"}}},
            {"agentId": "user_cccccccccccccccccccccccccccccccc",
              "match": {"channel": "openclaw-weixin", "accountId": "old-account",
                "peer": {"kind": "direct", "id": "wechat-user"}}}
          ]
        }
        """);
    when(fileService.paths("inst-1")).thenReturn(paths);
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(null);

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result.getOperationId()).isNotBlank();
    assertThat(result.getStatus()).isEqualTo("cleanup_failed");
    assertThat(result.getStage()).isEqualTo("validated");
    assertThat(result.getLastError()).contains("多个 Agent");
    verify(operationMapper).insert(result);
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
    verify(gatewayRpcService, never()).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
    verify(dataCleaner, never()).deleteOldUserData(anyString(), anyString(), any(), any());
    verify(mutationMapper, never()).deleteWechatAccount(anyString(), anyString());
  }

  @Test
  void preservesFilesAndDatabaseWhenGatewayReportsUnrelatedBindingConflict() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    UserAgentIdentityEntity identity = identity();
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of());
    when(gatewayRpcService.deleteUserAgent(instance, identity.getAgentId(), List.of("account-1"),
        List.of("wechat-user"), List.of(), List.of()))
        .thenReturn(new OpenClawGatewayRpcService.DeleteUserAgentResult(
            false, false, false, List.of(), List.of(java.util.Map.of("peerId", "other-user"))));

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result.getStatus()).isEqualTo("cleanup_failed");
    assertThat(result.getStage()).isEqualTo("channels_stopped");
    assertThat(result.getLastError()).contains("路由冲突");
    verify(dataCleaner, never()).deleteOldUserData(anyString(), anyString(), any(), any());
    verify(accountSyncService, never()).removeAccountStateFiles(any(), anyString());
    verify(identityMapper, never()).deleteByAgentId(anyString());
    verify(mutationMapper, never()).deleteWechatAccount(anyString(), anyString());
  }

  @Test
  void resumesInterruptedCleaningOperationFromItsLastSuccessfulStage() {
    InstanceEntity instance = instance();
    WechatUserCleanupOperationEntity interrupted = activeOperation("cleaning", "routing_deleted");
    interrupted.setAttemptCount(1);
    when(operationMapper.findByIdForUpdate("cleanup-existing")).thenReturn(interrupted);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance);

    WechatUserCleanupOperationEntity result = service.resume("cleanup-existing");

    assertThat(result.getStatus()).isEqualTo("completed");
    assertThat(result.getAttemptCount()).isEqualTo(2);
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
    verify(gatewayRpcService, never()).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
    verify(dataCleaner).deleteOldUserData("inst-1", "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", List.of(), List.of());
    verify(accountSyncService).removeAccountStateFiles(any(), eq("account-1"));
  }

  @Test
  void resumesAfterChannelsStoppedWithoutStoppingChannelAgain() {
    InstanceEntity instance = instance();
    WechatUserCleanupOperationEntity interrupted = activeOperation("cleaning", "channels_stopped");
    when(operationMapper.findByIdForUpdate("cleanup-existing")).thenReturn(interrupted);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance);
    when(gatewayRpcService.deleteUserAgent(instance, interrupted.getAgentId(), List.of("account-1"),
        List.of("wechat-user"), List.of(), List.of())).thenReturn(
        new OpenClawGatewayRpcService.DeleteUserAgentResult(true, true, true, List.of(), List.of()));

    WechatUserCleanupOperationEntity result = service.resume("cleanup-existing");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
    verify(gatewayRpcService).deleteUserAgent(instance, "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        List.of("account-1"), List.of("wechat-user"), List.of(), List.of());
  }

  @Test
  void resumesAfterLocalAgentDataDeletedWithoutDeletingRoutingOrAgentFilesAgain() {
    InstanceEntity instance = instance();
    WechatUserCleanupOperationEntity interrupted = activeOperation("cleaning", "local_agent_data_deleted");
    when(operationMapper.findByIdForUpdate("cleanup-existing")).thenReturn(interrupted);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance);

    WechatUserCleanupOperationEntity result = service.resume("cleanup-existing");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
    verify(gatewayRpcService, never()).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
    verify(dataCleaner, never()).deleteOldUserData(anyString(), anyString(), any(), any());
    verify(accountSyncService).removeAccountStateFiles(any(), eq("account-1"));
  }

  @Test
  void resumesAfterWechatFilesDeletedWithoutDeletingExternalStateAgain() {
    InstanceEntity instance = instance();
    WechatUserCleanupOperationEntity interrupted = activeOperation("cleaning", "wechat_files_deleted");
    when(operationMapper.findByIdForUpdate("cleanup-existing")).thenReturn(interrupted);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance);

    WechatUserCleanupOperationEntity result = service.resume("cleanup-existing");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
    verify(gatewayRpcService, never()).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
    verify(dataCleaner, never()).deleteOldUserData(anyString(), anyString(), any(), any());
    verify(accountSyncService, never()).removeAccountStateFiles(any(), anyString());
    verify(identityMapper).deleteByAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertThat(result.getDeletedDatabaseRows()).isZero();
  }

  @Test
  void resumesAfterDatabaseIdentityDeletedWithoutDeletingDatabaseAgain() {
    InstanceEntity instance = instance();
    WechatUserCleanupOperationEntity interrupted = activeOperation("cleaning", "database_identity_deleted");
    when(operationMapper.findByIdForUpdate("cleanup-existing")).thenReturn(interrupted);
    when(aggregateMapper.findById("inst-1")).thenReturn(instance);

    WechatUserCleanupOperationEntity result = service.resume("cleanup-existing");

    assertThat(result.getStatus()).isEqualTo("completed");
    verify(miniappKeyMapper, never()).deleteByAgentId(anyString());
    verify(miniappBindingMapper, never()).deleteByAgentId(anyString());
    verify(openVikingUserKeyMapper, never()).deleteByOpenvikingUserId(anyString());
    verify(identityMapper, never()).deleteByAgentId(anyString());
    verify(mutationMapper, never()).deleteWechatAccount(anyString(), anyString());
    verify(bindLinkMapper).redactByPhoneOrAccountId(any(), eq("account-1"), anyString());
  }

  @Test
  void rejectsCleanupWhileSameUserHasActiveBindLink() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setToken("bind-active");
    link.setStatus("waiting_scan");
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(bindLinkMapper.findActiveForUserForUpdate(
        eq("inst-1"), eq("13500000000"), eq("account-1"), eq("wechat-user"), anyString())).thenReturn(link);

    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> service.start(instance, "account-1", "user_center"))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("扫码绑定");

    verify(operationMapper, never()).insert(any());
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
  }

  @Test
  void rejectsCleanupWhileSameUserHasActiveRebindOperation() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    WechatRebindOperationEntity rebind = new WechatRebindOperationEntity();
    rebind.setBindToken("rebind-active");
    rebind.setStatus("cleaning");
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(rebindOperationMapper.findActiveForUserForUpdate("13500000000", "wechat-user"))
        .thenReturn(rebind);

    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> service.start(instance, "account-1", "user_center"))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("重新绑定");

    verify(operationMapper, never()).insert(any());
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
  }

  @Test
  void reusesActiveCleanupWhenIdentityOverlapsDespiteDifferentSubjectHash() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    UserAgentIdentityEntity identity = identity();
    WechatUserCleanupOperationEntity active = activeOperation("cleaning", "validated");
    active.setSubjectHash("older-subject-hash");
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of());
    when(operationMapper.findActiveByIdentityForUpdate(
        "inst-1", "13500000000", "wechat-user", "account-1",
        "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(active);

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result).isSameAs(active);
    verify(operationMapper, never()).insert(any());
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
  }

  @Test
  void repeatedStartReturnsExistingCleaningOperationWithoutExecutingItAgain() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    UserAgentIdentityEntity identity = identity();
    WechatUserCleanupOperationEntity active = activeOperation("cleaning", "validated");
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of());
    when(operationMapper.findActiveBySubjectForUpdate(eq("inst-1"), anyString())).thenReturn(active);

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result).isSameAs(active);
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
    verify(gatewayRpcService, never()).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
    verify(operationMapper, never()).insert(any());
  }

  @Test
  void repeatedStartDoesNotRetryExistingFailedOperation() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity account = account();
    UserAgentIdentityEntity identity = identity();
    WechatUserCleanupOperationEntity active = activeOperation("cleanup_failed", "channels_stopped");
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of());
    when(operationMapper.findActiveBySubjectForUpdate(eq("inst-1"), anyString())).thenReturn(active);

    WechatUserCleanupOperationEntity result = service.start(instance, "account-1", "user_center");

    assertThat(result).isSameAs(active);
    assertThat(result.getStatus()).isEqualTo("cleanup_failed");
    verify(gatewayRpcService, never()).stopWechatChannel(any(), any());
    verify(gatewayRpcService, never()).deleteUserAgent(any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void batchCleanupPersistsFailureOperationAndContinuesWithOtherUsers() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity disappeared = account();
    WechatPairedAccountEntity remaining = account();
    remaining.setAccountId("account-2");
    remaining.setPhone("13600000000");
    remaining.setWechatUserId("wechat-user-2");
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1")))
        .thenReturn(List.of(disappeared, remaining));
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(null);
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-2")).thenReturn(remaining);

    List<WechatUserCleanupOperationEntity> operations = service.startAll(instance);

    assertThat(operations).hasSize(2);
    assertThat(operations.get(0).getOperationId()).isNotBlank();
    assertThat(operations.get(0).getStatus()).isEqualTo("cleanup_failed");
    assertThat(operations.get(0).getSource()).isEqualTo("instance_unbind");
    assertThat(operations.get(1).getStatus()).isEqualTo("completed");
    verify(accountSyncService).removeAccountStateFiles(any(), eq("account-2"));
  }

  @Test
  void batchFailureReusesCleanupMatchingAnyStrongIdentityField() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity disappeared = account();
    WechatUserCleanupOperationEntity active = activeOperation("cleanup_failed", "validated");
    active.setSubjectHash("legacy-subject-hash");
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1")))
        .thenReturn(List.of(disappeared));
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(null);
    when(operationMapper.findActiveByIdentityForUpdate(
        "inst-1", "13500000000", "wechat-user", "account-1", null)).thenReturn(active);

    List<WechatUserCleanupOperationEntity> operations = service.startAll(instance);

    assertThat(operations).containsExactly(active);
    verify(operationMapper, never()).insert(any());
  }

  @Test
  void batchCleanupProcessesEveryAccountIndependently() {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity first = account();
    WechatPairedAccountEntity second = account();
    second.setAccountId("account-2");
    second.setPhone("13600000000");
    second.setWechatUserId("wechat-user-2");
    when(aggregateMapper.listWechatAccountsByInstanceIds(List.of("inst-1"))).thenReturn(List.of(first, second));
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(first);
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-2")).thenReturn(second);

    List<WechatUserCleanupOperationEntity> operations = service.startAll(instance);

    assertThat(operations).hasSize(2);
    assertThat(operations).allSatisfy(operation -> assertThat(operation.getStatus()).isEqualTo("completed"));
    verify(accountSyncService).removeAccountStateFiles(any(), eq("account-1"));
    verify(accountSyncService).removeAccountStateFiles(any(), eq("account-2"));
  }
  @Test
  void serializesCleanupExecutionWithinTheSameInstance() throws Exception {
    InstanceEntity instance = instance();
    WechatPairedAccountEntity first = account();
    WechatPairedAccountEntity second = account();
    second.setAccountId("account-2");
    second.setPhone("13600000000");
    second.setWechatUserId("wechat-user-2");
    UserAgentIdentityEntity firstIdentity = identity();
    UserAgentIdentityEntity secondIdentity = identity();
    secondIdentity.setAgentId("user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    secondIdentity.setWechatUserId("wechat-user-2");
    secondIdentity.setOpenvikingUserId("wx-memory-2");
    configureConcurrentCleanup(first, second, firstIdentity, secondIdentity);
    java.util.concurrent.ConcurrentMap<String, WechatUserCleanupOperationEntity> operations =
        new java.util.concurrent.ConcurrentHashMap<>();
    when(operationMapper.insert(any())).thenAnswer(invocation -> {
      WechatUserCleanupOperationEntity value = invocation.getArgument(0);
      operations.put(value.getOperationId(), value);
      return 1;
    });
    when(operationMapper.update(any())).thenAnswer(invocation -> {
      WechatUserCleanupOperationEntity value = invocation.getArgument(0);
      operations.put(value.getOperationId(), value);
      return 1;
    });
    when(operationMapper.findById(anyString())).thenAnswer(
        invocation -> operations.get(invocation.getArgument(0)));
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    doAnswer(invocation -> {
      int current = active.incrementAndGet();
      maximum.accumulateAndGet(current, Math::max);
      if (firstEntered.getCount() > 0) {
        firstEntered.countDown();
        assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
      }
      active.decrementAndGet();
      return null;
    }).when(gatewayRpcService).stopWechatChannel(eq(instance), any());

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WechatUserCleanupOperationEntity> firstResult = executor.submit(
          () -> service.start(instance, "account-1", "user_center"));
      assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<WechatUserCleanupOperationEntity> secondResult = executor.submit(
          () -> service.start(instance, "account-2", "user_center"));

      Thread.sleep(200);
      assertThat(maximum.get()).isEqualTo(1);
      releaseFirst.countDown();
      assertThat(firstResult.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("completed");
      assertThat(secondResult.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("completed");
      assertThat(maximum.get()).isEqualTo(1);
    }
  }

  @Test
  void allowsCleanupExecutionAcrossDifferentInstancesInParallel() throws Exception {
    InstanceEntity firstInstance = instance();
    InstanceEntity secondInstance = instance();
    secondInstance.setId("inst-2");
    WechatPairedAccountEntity first = account();
    WechatPairedAccountEntity second = account();
    second.setInstanceId("inst-2");
    second.setAccountId("account-2");
    second.setPhone("13600000000");
    second.setWechatUserId("wechat-user-2");
    UserAgentIdentityEntity firstIdentity = identity();
    UserAgentIdentityEntity secondIdentity = identity();
    secondIdentity.setAgentId("user_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    secondIdentity.setWechatUserId("wechat-user-2");
    secondIdentity.setOpenvikingUserId("wx-memory-2");
    configureConcurrentCleanup(first, second, firstIdentity, secondIdentity);
    java.util.concurrent.ConcurrentMap<String, WechatUserCleanupOperationEntity> operations =
        new java.util.concurrent.ConcurrentHashMap<>();
    when(operationMapper.insert(any())).thenAnswer(invocation -> {
      WechatUserCleanupOperationEntity value = invocation.getArgument(0);
      operations.put(value.getOperationId(), value);
      return 1;
    });
    when(operationMapper.update(any())).thenAnswer(invocation -> {
      WechatUserCleanupOperationEntity value = invocation.getArgument(0);
      operations.put(value.getOperationId(), value);
      return 1;
    });
    when(operationMapper.findById(anyString())).thenAnswer(
        invocation -> operations.get(invocation.getArgument(0)));    when(fileService.paths("inst-2")).thenReturn(
        new InstancePaths(temp.resolve("inst-2"), temp.resolve("inst-2/home"),
            temp.resolve("inst-2/workspace"), temp.resolve("inst-2/logs")));

    CountDownLatch bothEntered = new CountDownLatch(2);
    CountDownLatch releaseBoth = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    doAnswer(invocation -> {
      int current = active.incrementAndGet();
      maximum.accumulateAndGet(current, Math::max);
      bothEntered.countDown();
      assertThat(releaseBoth.await(5, TimeUnit.SECONDS)).isTrue();
      active.decrementAndGet();
      return null;
    }).when(gatewayRpcService).stopWechatChannel(any(), any());

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WechatUserCleanupOperationEntity> firstResult = executor.submit(
          () -> service.start(firstInstance, "account-1", "user_center"));
      Future<WechatUserCleanupOperationEntity> secondResult = executor.submit(
          () -> service.start(secondInstance, "account-2", "user_center"));

      assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(maximum.get()).isEqualTo(2);
      releaseBoth.countDown();
      assertThat(firstResult.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("completed");
      assertThat(secondResult.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("completed");
    }
  }

  private void configureSuccessfulCleanup(InstanceEntity instance) {
    WechatPairedAccountEntity account = account();
    UserAgentIdentityEntity identity = identity();
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate("account-1")).thenReturn(account);
    when(identityMapper.findByWechatUserIdForUpdate("wechat-user")).thenReturn(identity);
    when(miniappBindingMapper.listByAgentId(identity.getAgentId())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds("inst-1", identity.getAgentId())).thenReturn(List.of());
    when(gatewayRpcService.deleteUserAgent(instance, identity.getAgentId(), List.of("account-1"),
        List.of("wechat-user"), List.of(), List.of())).thenReturn(
        new OpenClawGatewayRpcService.DeleteUserAgentResult(true, true, true, List.of(), List.of()));
  }

  private void configureConcurrentCleanup(
      WechatPairedAccountEntity first,
      WechatPairedAccountEntity second,
      UserAgentIdentityEntity firstIdentity,
      UserAgentIdentityEntity secondIdentity) {
    when(aggregateMapper.findWechatAccountByAccountIdForUpdate(anyString())).thenAnswer(invocation ->
        "account-1".equals(invocation.getArgument(0)) ? first : second);
    when(identityMapper.findByWechatUserIdForUpdate(anyString())).thenAnswer(invocation ->
        "wechat-user".equals(invocation.getArgument(0)) ? firstIdentity : secondIdentity);
    when(miniappBindingMapper.listByAgentId(anyString())).thenReturn(List.of());
    when(dataCleaner.readOldSessionIds(anyString(), anyString())).thenReturn(List.of());
    when(gatewayRpcService.deleteUserAgent(any(), anyString(), any(), any(), any(), any())).thenReturn(
        new OpenClawGatewayRpcService.DeleteUserAgentResult(true, true, true, List.of(), List.of()));
    when(openClawRuntime.inspectInstance(any())).thenReturn(new RuntimeState(false, "stopped", "now"));
  }

  private InstancePaths paths() throws Exception {
    Path home = temp.resolve("home");
    Files.createDirectories(home);
    return new InstancePaths(temp, home, temp.resolve("workspace"), temp.resolve("logs"));
  }

  private static WechatUserCleanupOperationEntity activeOperation(String status, String stage) {
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId("cleanup-existing");
    operation.setInstanceId("inst-1");
    operation.setSubjectHash("subject");
    operation.setAccountId("account-1");
    operation.setWechatUserId("wechat-user");
    operation.setAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    operation.setApiPeerIdsJson("[]");
    operation.setOldSessionIdsJson("[]");
    operation.setProtectedAgentIdsJson("[]");
    operation.setStatus(status);
    operation.setStage(stage);
    operation.setAttemptCount(1);
    return operation;
  }

  private static InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst-1");
    instance.setStatus("running");
    return instance;
  }

  private static WechatPairedAccountEntity account() {
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setInstanceId("inst-1");
    account.setAccountId("account-1");
    account.setPhone("13500000000");
    account.setWechatUserId("wechat-user");
    return account;
  }

  private static UserAgentIdentityEntity identity() {
    UserAgentIdentityEntity identity = new UserAgentIdentityEntity();
    identity.setAgentId("user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    identity.setWechatUserId("wechat-user");
    identity.setOpenvikingUserId("wx-memory");
    return identity;
  }

  private static final class DirectExecutorService extends AbstractExecutorService {
    private boolean shutdown;
    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    @Override public void execute(Runnable command) { command.run(); }
  }

  private static final class ManualExecutorService extends AbstractExecutorService {
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;
    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; List<Runnable> queued = List.copyOf(tasks); tasks.clear(); return queued; }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    @Override public void execute(Runnable command) {
      if (shutdown) throw new RejectedExecutionException("executor shut down");
      tasks.add(command);
    }
    int pendingTasks() { return tasks.size(); }
    void runNext() { tasks.remove().run(); }
  }

  private static final class RejectingExecutorService extends AbstractExecutorService {
    @Override public void shutdown() {}
    @Override public List<Runnable> shutdownNow() { return List.of(); }
    @Override public boolean isShutdown() { return false; }
    @Override public boolean isTerminated() { return false; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
    @Override public void execute(Runnable command) { throw new RejectedExecutionException("queue full"); }
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final AtomicBoolean active = new AtomicBoolean();
    @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
      active.set(true);
      return new SimpleTransactionStatus();
    }
    @Override public void commit(TransactionStatus status) { active.set(false); }
    @Override public void rollback(TransactionStatus status) { active.set(false); }
  }
}







