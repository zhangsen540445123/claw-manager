package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WechatBindLinkServiceTest {

  @Mock
  WechatBindLinkMapper linkMapper;

  @Mock
  InstanceAggregateMapper aggregateMapper;

  @Mock
  InstanceMutationMapper mutationMapper;

  @Mock
  WechatBindService wechatBindService;

  @Mock
  WechatAccountSyncService accountSyncService;

  @Mock
  InstanceFileService fileService;

  @Mock
  InstanceEventPublisher eventPublisher;

  @Mock
  OpenClawGatewayRpcService gatewayRpcService;

  WechatBindLinkService service;
  QueuedExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new QueuedExecutor();
    service = new WechatBindLinkService(
        linkMapper,
        aggregateMapper,
        mutationMapper,
        wechatBindService,
        accountSyncService,
        fileService,
        new ClawbotProperties(null, null, null, null),
        new ObjectMapper(),
        eventPublisher,
        gatewayRpcService,
        executor
    );
  }

  @Test
  void createsNewUserLinkWithAdminPhoneAndSchedulesQr() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    when(aggregateMapper.listAll()).thenReturn(List.of(instance));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));
    when(aggregateMapper.countWechatAccountsByInstanceId("inst_1")).thenReturn(0);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);

    PublicWechatBindLink link = service.createLink(
        admin(),
        new WechatBindLinkService.CreateBindLinkRequest("new", "135 7287 3189"),
        "https://admin.example.test"
    );

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).insert(captor.capture());
    WechatBindLinkEntity stored = captor.getValue();
    assertThat(stored.getToken()).startsWith("wbl_");
    assertThat(stored.getMode()).isEqualTo("new");
    assertThat(stored.getStatus()).isEqualTo("created");
    assertThat(stored.getPhone()).isEqualTo("13572873189");
    assertThat(stored.getInstanceId()).isEqualTo("inst_1");
    assertThat(stored.getCreatedByAdminId()).isEqualTo("admin_1");
    assertThat(Instant.parse(stored.getExpiresAt())).isAfter(Instant.now().plusSeconds(86_000));
    assertThat(link.status()).isEqualTo("created");
    assertThat(link.statusLabel()).isEqualTo("已创建");
    assertThat(link.modeLabel()).isEqualTo("新用户");
    assertThat(link.phone()).isEqualTo("13572873189");
    assertThat(link.qrLink()).isEmpty();
    assertThat(link.message()).isEqualTo("正在准备微信扫码二维码，请稍候。");
    assertThat(link.bindLink()).startsWith("https://admin.example.test/bind/wbl_");
    assertThat(executor.size()).isEqualTo(1);
    verify(wechatBindService, never()).startBind(eq(instance), eq(false), any(), any());
  }

  @Test
  void createsMiniappLinkForFixedInstanceAndStoresOpenidHash() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    PublicWechatBindLink link = service.createMiniappLink(
        "openid_hash_1",
        "inst_1",
        "",
        "https://miniapp.example.test"
    );

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).insert(captor.capture());
    WechatBindLinkEntity stored = captor.getValue();
    assertThat(stored.getMode()).isEqualTo("new");
    assertThat(stored.getPhone()).isNull();
    assertThat(stored.getInstanceId()).isEqualTo("inst_1");
    assertThat(stored.getMiniappOpenidHash()).isEqualTo("openid_hash_1");
    assertThat(stored.getCreatedByAdminId()).isNull();
    assertThat(link.status()).isEqualTo("created");
    assertThat(executor.size()).isEqualTo(1);
  }

  @Test
  void rejectsNewUserLinkWhenNoReadyRunningInstanceExists() {
    when(aggregateMapper.listAll()).thenReturn(List.of(instance("inst_1", "实例一", "stopped")));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    assertThatThrownBy(() -> service.createLink(
        admin(),
        new WechatBindLinkService.CreateBindLinkRequest("new", "13572873189"),
        "https://admin.example.test"
    ))
        .hasMessage("当前暂无可用 OpenClaw 实例，请先启动并等待实例就绪。");
  }

  @Test
  void createsExistingUserLinkForOriginalInstanceAndSchedulesQr() {
    WechatPairedAccountEntity account = pairedAccount("wx_existing", "13572873189", "inst_1");
    when(aggregateMapper.findWechatAccountByPhone("13572873189")).thenReturn(account);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance("inst_1", "老用户实例", "running"));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    PublicWechatBindLink link = service.createLink(
        admin(),
        new WechatBindLinkService.CreateBindLinkRequest("existing", "135 7287 3189"),
        "https://admin.example.test"
    );

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).insert(captor.capture());
    WechatBindLinkEntity stored = captor.getValue();
    assertThat(stored.getMode()).isEqualTo("existing");
    assertThat(stored.getStatus()).isEqualTo("created");
    assertThat(stored.getPhone()).isEqualTo("13572873189");
    assertThat(stored.getInstanceId()).isEqualTo("inst_1");
    assertThat(stored.getTargetAccountId()).isEqualTo("wx_existing");
    assertThat(link.instanceName()).isEqualTo("老用户实例");
    assertThat(link.status()).isEqualTo("created");
    assertThat(link.qrLink()).isEmpty();
    assertThat(executor.size()).isEqualTo(1);
    verify(wechatBindService, never()).startBind(any(), eq(false), eq("wx_existing"), any());
  }

  @Test
  void searchesBindingsByPhoneKeyword() {
    WechatPairedAccountEntity account = pairedAccount("wx_existing", "13572873189", "inst_1");
    when(aggregateMapper.searchWechatAccountsByPhoneKeyword("5728")).thenReturn(List.of(account));

    List<WechatPairedAccountEntity> result = service.searchBindingsByPhoneKeyword(" 57 28 ");

    assertThat(result).containsExactly(account);
    verify(aggregateMapper).searchWechatAccountsByPhoneKeyword("5728");
  }

  @Test
  void returnsNoBindingsForBlankPhoneKeyword() {
    assertThat(service.searchBindingsByPhoneKeyword("  ")).isEmpty();
  }

  @Test
  void rejectsNewUserLinkWhenPhoneAlreadyHasABinding() {
    when(aggregateMapper.findWechatAccountByPhone("13572873189"))
        .thenReturn(pairedAccount("wx_existing", "13572873189", "inst_1"));

    assertThatThrownBy(() -> service.createLink(
        admin(),
        new WechatBindLinkService.CreateBindLinkRequest("new", "13572873189"),
        "https://admin.example.test"
    ))
        .hasMessage("该手机号已绑定，请使用老用户出码。");
    verify(linkMapper, never()).insert(any());
    verify(wechatBindService, never()).startBind(any(), eq(false), any(), any());
  }

  @Test
  void backgroundQrTaskUpdatesLinkToWaitingScan() {
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>();
    when(linkMapper.insert(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(linkMapper.findByToken(any())).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    InstanceEntity busy = instance("inst_busy", "繁忙实例", "running");
    InstanceEntity idle = instance("inst_idle", "空闲实例", "running");
    InstanceEntity stopped = instance("inst_stopped", "停止实例", "stopped");
    when(aggregateMapper.listAll()).thenReturn(List.of(busy, idle, stopped));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_busy", "inst_idle", "inst_stopped")))
        .thenReturn(List.of(readyProvisioning("inst_busy"), readyProvisioning("inst_idle")));
    when(aggregateMapper.countWechatAccountsByInstanceId("inst_busy")).thenReturn(2);
    when(aggregateMapper.countWechatAccountsByInstanceId("inst_idle")).thenReturn(1);
    when(aggregateMapper.findById("inst_idle")).thenReturn(idle);
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_idle"))).thenReturn(List.of(readyProvisioning("inst_idle")));
    when(wechatBindService.startBind(eq(idle), eq(false), any(), any()))
        .thenAnswer(invocation -> startResult(invocation.getArgument(2), "https://liteapp.weixin.qq.com/q/new-user"));

    PublicWechatBindLink link = service.createLink(
        admin(),
        new WechatBindLinkService.CreateBindLinkRequest("new", "13572873189"),
        "https://admin.example.test"
    );

    assertThat(link.status()).isEqualTo("created");
    assertThat(link.phone()).isEqualTo("13572873189");
    assertThat(link.instanceId()).isEqualTo("inst_idle");
    assertThat(link.qrLink()).isEmpty();

    executor.runNext();

    assertThat(saved.get().getStatus()).isEqualTo("waiting_scan");
    assertThat(saved.get().getQrLink()).isEqualTo("https://liteapp.weixin.qq.com/q/new-user");
    assertThat(saved.get().getTargetAccountId()).startsWith("cmwx_wbl_");
    assertThat(saved.get().getStartedAt()).isNotBlank();
    verify(wechatBindService).startBind(eq(idle), eq(false), any(), any());
    verify(eventPublisher, atLeastOnce()).publishWechatBindLinkUpdated(eq(saved.get().getToken()), any(PublicWechatBindLink.class));
  }

  @Test
  void publicStatusDoesNotCopyWaitingScanQrFromInstanceBinding() {
    WechatBindLinkEntity stored = existingLink("token_3", "inst_1");
    stored.setStatus("starting");
    when(linkMapper.findByToken("token_3")).thenReturn(stored);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance("inst_1", "实例一", "running"));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    PublicWechatBindLink link = service.getPublicStatus("token_3", "https://admin.example.test");

    verify(linkMapper, never()).update(any());
    assertThat(link.status()).isEqualTo("starting");
    assertThat(link.qrPayload()).isEmpty();
    assertThat(link.message()).isEqualTo("正在准备微信扫码二维码，请稍候。");
  }

  @Test
  void publicStatusExpiresOldQrAndClearsPayload() {
    WechatBindLinkEntity stored = existingLink("token_4", "inst_1");
    stored.setStatus("waiting_scan");
    stored.setQrPayload("data:image/png;base64,abc");
    stored.setQrLink("https://qr.example.test");
    stored.setQrExpiresAt(Instant.now().minusSeconds(1).toString());
    when(linkMapper.findByToken("token_4")).thenReturn(stored);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance("inst_1", "实例一", "running"));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    PublicWechatBindLink link = service.getPublicStatus("token_4", "https://admin.example.test");

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).update(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("expired");
    assertThat(captor.getValue().getQrPayload()).isEmpty();
    assertThat(link.status()).isEqualTo("expired");
    assertThat(link.qrExpired()).isFalse();
    assertThat(link.qrPayload()).isEmpty();
    assertThat(link.message()).isEqualTo("二维码已过期，请重新生成后扫码绑定。");
  }

  @Test
  void publicStatusTellsUserToWaitWhenConnectedInstanceIsRestarting() {
    WechatBindLinkEntity stored = existingLink("token_5", "inst_1");
    stored.setStatus("connected");
    when(linkMapper.findByToken("token_5")).thenReturn(stored);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance("inst_1", "实例一", "running"));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(runningProvisioning("inst_1")));

    PublicWechatBindLink link = service.getPublicStatus("token_5", "https://admin.example.test");

    assertThat(link.status()).isEqualTo("connected");
    assertThat(link.message()).isEqualTo("微信已绑定成功，OpenClaw 实例正在准备中，请稍后再使用。");
  }

  @Test
  void existingUserLinkDoesNotBecomeConnectedFromStaleInstanceBindingBeforeQrCompletes() {
    WechatBindLinkEntity stored = existingLink("token_existing_stale_connected", "inst_1");
    stored.setStatus("starting");
    when(linkMapper.findByToken("token_existing_stale_connected")).thenReturn(stored);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance("inst_1", "实例一", "running"));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    PublicWechatBindLink link = service.getPublicStatus("token_existing_stale_connected", "https://admin.example.test");

    assertThat(link.status()).isEqualTo("starting");
    assertThat(link.message()).isEqualTo("正在准备微信扫码二维码，请稍候。");
    verify(linkMapper, never()).update(any());
    verify(accountSyncService, never()).readRawAccounts(any(), any());
  }

  @Test
  void newUserLinkDoesNotRejectFromStaleConnectedInstanceBindingBeforeScanCompletes() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = newLink("token_new_stale_connected");
    stored.setStatus("waiting_scan");
    stored.setPhone("13900000001");
    stored.setInstanceId("inst_1");
    stored.setTargetAccountId("cmwx_stale_connected");
    stored.setQrMode("link");
    stored.setQrLink("https://qr.example.test");
    stored.setQrExpiresAt(Instant.now().plusSeconds(60).toString());
    stored.setUpdatedAt("2026-06-20T11:12:30Z");
    when(linkMapper.findByToken("token_new_stale_connected")).thenReturn(stored);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    PublicWechatBindLink link = service.getPublicStatus("token_new_stale_connected", "https://admin.example.test");

    assertThat(link.status()).isEqualTo("waiting_scan");
    assertThat(link.message()).isEqualTo("请使用微信扫描二维码完成绑定。");
    verify(linkMapper, never()).update(any());
    verify(accountSyncService, never()).readRawAccounts(any(), any());
    verify(mutationMapper, never()).insertWechatAccount(any());
    verify(gatewayRpcService, never()).restartWechatChannel(any(), any());
  }

  @Test
  void existingUserLinkIgnoresCompletionFromAnotherLoginRequest() {
    WechatBindLinkEntity stored = existingLink("token_existing_stale_raw", "inst_1");
    stored.setStatus("waiting_scan");
    stored.setTargetAccountId("wx_existing");
    stored.setStartedAt("2026-06-20T08:17:49Z");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_existing_stale_raw")).thenAnswer(invocation -> saved.get());

    service.completeBindAfterLogin(
        "token_existing_stale_raw",
        completion("other_request", "wx_existing", "wechat-user"),
        "https://admin.example.test"
    );

    assertThat(saved.get().getStatus()).isEqualTo("waiting_scan");
    verify(linkMapper, never()).update(any());
    verify(accountSyncService, never()).syncInstanceAccounts(any());
    verify(gatewayRpcService, never()).restartWechatChannel(any(), any());
  }

  @Test
  void existingUserLinkConnectsOnlyAfterRawAccountRefreshAndRestartsThatChannel() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = existingLink("token_existing_fresh_raw", "inst_1");
    stored.setStatus("waiting_scan");
    stored.setTargetAccountId("wx_existing");
    stored.setStartedAt("2026-06-20T08:17:49Z");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_existing_fresh_raw")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));
    when(aggregateMapper.findWechatAccountByAccountId("wx_existing")).thenReturn(pairedAccount("wx_existing", "13572873189", "inst_1"));

    service.completeBindAfterLogin(
        "token_existing_fresh_raw",
        completion("wx_existing", "wx_existing", "wechat-user"),
        "https://admin.example.test"
    );

    assertThat(saved.get().getStatus()).isEqualTo("connected");
    verify(accountSyncService).syncInstanceAccounts(instance);
    verify(gatewayRpcService).restartWechatChannel(instance, List.of("wx_existing"));
  }

  @Test
  void rejectsNewUserWhenWechatUserIdAlreadyBelongsToAnotherAccount() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = newLink("token_duplicate_wechat");
    stored.setStatus("waiting_scan");
    stored.setPhone("13900000001");
    stored.setInstanceId("inst_1");
    stored.setTargetAccountId("cmwx_duplicate_wechat");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_duplicate_wechat")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.findWechatAccountByAccountId("cmwx_duplicate_wechat")).thenReturn(null);
    when(aggregateMapper.findWechatAccountByWechatUserId("wechat-user"))
        .thenReturn(pairedAccount("wx_existing", "13800000000", "inst_1"));
    when(fileService.paths("inst_1")).thenReturn(testPaths());

    service.completeBindAfterLogin(
        "token_duplicate_wechat",
        completion("cmwx_duplicate_wechat", "cmwx_duplicate_wechat", "wechat-user"),
        "https://admin.example.test"
    );

    assertThat(saved.get().getStatus()).isEqualTo("rejected");
    assertThat(saved.get().getErrorMessage()).isEqualTo("该微信已绑定到其他手机号或实例，请联系管理员处理。");
    verify(mutationMapper, never()).insertWechatAccount(any());
    verify(accountSyncService).removeAccountStateFiles(any(InstancePaths.class), eq("cmwx_duplicate_wechat"));
    verify(gatewayRpcService).restartWechatChannel(instance, List.of("wx_existing"));
  }

  @Test
  void duplicateWechatScanRefreshesOriginalAccountCredentialBeforeCleaningRejectedLogin() {
    InstanceEntity currentInstance = instance("inst_current", "当前实例", "running");
    InstanceEntity originalInstance = instance("inst_original", "原实例", "running");
    WechatPairedAccountEntity existing = pairedAccount("wx_existing", "13572873189", "inst_original");
    existing.setWechatUserId("wechat-user");
    WechatBindLinkEntity stored = newLink("token_duplicate_refresh");
    stored.setStatus("waiting_scan");
    stored.setPhone("13900000001");
    stored.setInstanceId("inst_current");
    stored.setTargetAccountId("cmwx_duplicate_refresh");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_duplicate_refresh")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_current")).thenReturn(currentInstance);
    when(aggregateMapper.findById("inst_original")).thenReturn(originalInstance);
    when(aggregateMapper.findWechatAccountByAccountId("a138ffbbc45f-im-bot")).thenReturn(null);
    when(aggregateMapper.findWechatAccountByWechatUserId("wechat-user")).thenReturn(existing);
    when(fileService.paths("inst_current")).thenReturn(testPaths());

    service.completeBindAfterLogin(
        "token_duplicate_refresh",
        completion("cmwx_duplicate_refresh", "a138ffbbc45f-im-bot", "wechat-user"),
        "https://admin.example.test"
    );

    assertThat(saved.get().getStatus()).isEqualTo("rejected");
    InOrder order = inOrder(accountSyncService, gatewayRpcService);
    order.verify(accountSyncService).refreshAccountCredentialsFromRejectedLogin(
        currentInstance,
        "a138ffbbc45f-im-bot",
        existing
    );
    order.verify(accountSyncService).removeAccountStateFiles(any(InstancePaths.class), eq("cmwx_duplicate_refresh"));
    order.verify(accountSyncService).removeAccountStateFiles(any(InstancePaths.class), eq("a138ffbbc45f-im-bot"));
    order.verify(gatewayRpcService).restartWechatChannel(originalInstance, List.of("wx_existing"));
    verify(mutationMapper, never()).insertWechatAccount(any());
  }

  @Test
  void rejectsNewUserWhenDuplicateWechatReusesExistingRawAccountWithoutAddingPendingAccount() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = newLink("token_duplicate_reused_account");
    stored.setStatus("waiting_scan");
    stored.setPhone("13900000001");
    stored.setInstanceId("inst_1");
    stored.setTargetAccountId("cmwx_duplicate_reused");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_duplicate_reused_account")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.findWechatAccountByWechatUserId("wechat-user"))
        .thenReturn(pairedAccount("wx_existing", "13572873189", "inst_1"));
    when(fileService.paths("inst_1")).thenReturn(testPaths());

    service.completeBindAfterLogin(
        "token_duplicate_reused_account",
        completion("cmwx_duplicate_reused", "wx_existing", "wechat-user"),
        "https://admin.example.test"
    );

    assertThat(saved.get().getStatus()).isEqualTo("rejected");
    assertThat(saved.get().getErrorMessage()).isEqualTo("该微信已绑定到其他手机号或实例，请联系管理员处理。");
    verify(mutationMapper, never()).insertWechatAccount(any());
    verify(accountSyncService).removeAccountStateFiles(any(InstancePaths.class), eq("cmwx_duplicate_reused"));
    verify(accountSyncService, never()).removeAccountStateFiles(any(InstancePaths.class), eq("wx_existing"));
    verify(gatewayRpcService).restartWechatChannel(instance, List.of("wx_existing"));
  }

  @Test
  void acceptsNewUserOnlyWhenPluginCompletionReturnsActualAccountAndWechatUserId() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = newLink("token_plugin_generated_account");
    stored.setStatus("waiting_scan");
    stored.setPhone("13900000001");
    stored.setInstanceId("inst_1");
    stored.setTargetAccountId("cmwx_plugin_generated_account");
    stored.setStartedAt("2026-06-20T12:45:16Z");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_plugin_generated_account")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.findWechatAccountByWechatUserId("wechat-new-user")).thenReturn(null);
    when(aggregateMapper.findWechatAccountByAccountId("554603a4df61-im-bot")).thenReturn(null);

    service.completeBindAfterLogin(
        "token_plugin_generated_account",
        completion("cmwx_plugin_generated_account", "554603a4df61-im-bot", "wechat-new-user"),
        "https://admin.example.test"
    );

    assertThat(saved.get().getStatus()).isEqualTo("connected");
    assertThat(saved.get().getScannedWechatUserId()).isEqualTo("wechat-new-user");
    ArgumentCaptor<WechatPairedAccountEntity> accountCaptor = ArgumentCaptor.forClass(WechatPairedAccountEntity.class);
    verify(mutationMapper).insertWechatAccount(accountCaptor.capture());
    assertThat(accountCaptor.getValue().getAccountId()).isEqualTo("554603a4df61-im-bot");
    assertThat(accountCaptor.getValue().getWechatUserId()).isEqualTo("wechat-new-user");
    assertThat(accountCaptor.getValue().getPhone()).isEqualTo("13900000001");
    verify(accountSyncService, never()).readRawAccounts(any(), any());
    verify(gatewayRpcService).restartWechatChannel(instance, List.of("554603a4df61-im-bot"));
  }

  @Test
  void miniappNewUserBindingStoresWechatAccountWithoutPhone() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = newLink("token_miniapp_no_phone");
    stored.setStatus("waiting_scan");
    stored.setPhone(null);
    stored.setMiniappOpenidHash("openid_hash_1");
    stored.setInstanceId("inst_1");
    stored.setTargetAccountId("cmwx_miniapp_no_phone");
    stored.setStartedAt("2026-06-20T12:45:16Z");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_miniapp_no_phone")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.findWechatAccountByWechatUserId("wechat-miniapp-user")).thenReturn(null);
    when(aggregateMapper.findWechatAccountByAccountId("554603a4df61-im-bot")).thenReturn(null);

    service.completeBindAfterLogin(
        "token_miniapp_no_phone",
        completion("cmwx_miniapp_no_phone", "554603a4df61-im-bot", "wechat-miniapp-user"),
        "https://admin.example.test"
    );

    ArgumentCaptor<WechatPairedAccountEntity> accountCaptor = ArgumentCaptor.forClass(WechatPairedAccountEntity.class);
    verify(mutationMapper).insertWechatAccount(accountCaptor.capture());
    assertThat(accountCaptor.getValue().getPhone()).isNull();
    assertThat(saved.get().getStatus()).isEqualTo("connected");
  }

  @Test
  void rejectsNewUserWhenRawWechatUserIdIsMissing() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = newLink("token_missing_wechat_user");
    stored.setStatus("waiting_scan");
    stored.setPhone("13900000001");
    stored.setInstanceId("inst_1");
    stored.setTargetAccountId("cmwx_missing_wechat_user");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_missing_wechat_user")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(fileService.paths("inst_1")).thenReturn(testPaths());

    service.completeBindAfterLogin(
        "token_missing_wechat_user",
        completion("cmwx_missing_wechat_user", "cmwx_missing_wechat_user", ""),
        "https://admin.example.test"
    );

    assertThat(saved.get().getStatus()).isEqualTo("rejected");
    assertThat(saved.get().getErrorMessage()).isEqualTo("无法识别扫码微信用户，请重新扫码或联系管理员处理。");
    verify(mutationMapper, never()).insertWechatAccount(any());
    verify(accountSyncService).removeAccountStateFiles(any(InstancePaths.class), eq("cmwx_missing_wechat_user"));
  }

  @Test
  void marksNewUserLinkFailedWhenCliQrStartFails() {
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>();
    when(linkMapper.insert(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(linkMapper.findByToken(any())).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    when(aggregateMapper.listAll()).thenReturn(List.of(instance));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));
    when(aggregateMapper.countWechatAccountsByInstanceId("inst_1")).thenReturn(0);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(wechatBindService.startBind(eq(instance), eq(false), any(), any()))
        .thenThrow(new IllegalStateException("微信二维码生成失败：未捕获二维码链接。"));

    PublicWechatBindLink link = service.createLink(
        admin(),
        new WechatBindLinkService.CreateBindLinkRequest("new", "13900000002"),
        "https://admin.example.test"
    );

    assertThat(link.status()).isEqualTo("created");
    assertThat(link.qrLink()).isEmpty();

    executor.runNext();

    assertThat(saved.get().getStatus()).isEqualTo("failed");
    assertThat(saved.get().getErrorMessage()).isEqualTo("微信二维码生成失败：未捕获二维码链接。");
    assertThat(saved.get().getTargetAccountId()).startsWith("cmwx_wbl_");
  }

  @Test
  void publicStatusExpiresLinkAfterOneDay() {
    WechatBindLinkEntity stored = newLink("token_expired");
    stored.setStatus("starting");
    stored.setExpiresAt(Instant.now().minusSeconds(1).toString());
    stored.setQrPayload("data:image/png;base64,abc");
    stored.setQrLink("https://qr.example.test");
    when(linkMapper.findByToken("token_expired")).thenReturn(stored);

    PublicWechatBindLink link = service.getPublicStatus("token_expired", "https://admin.example.test");

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).update(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("expired");
    assertThat(captor.getValue().getQrPayload()).isEmpty();
    assertThat(link.status()).isEqualTo("expired");
    assertThat(link.statusLabel()).isEqualTo("已过期");
    assertThat(link.message()).isEqualTo("扫码链接已过期，请联系管理员重新生成。");
  }

  @Test
  void refreshAllowsQrExpiredLinkWhenOneDayTtlIsStillValidAndSchedulesQr() {
    WechatBindLinkEntity stored = existingLink("token_qr_expired", "inst_1");
    stored.setStatus("expired");
    stored.setErrorMessage("二维码已过期，请重新生成后扫码绑定。");
    stored.setExpiresAt(Instant.now().plusSeconds(3600).toString());
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_qr_expired")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));
    when(wechatBindService.startBind(eq(instance), eq(true), eq("wx_existing"), any()))
        .thenReturn(startResult("wx_existing", "https://liteapp.weixin.qq.com/q/refresh"));

    PublicWechatBindLink link = service.refreshQr("token_qr_expired", "https://admin.example.test");

    assertThat(link.status()).isEqualTo("starting");
    assertThat(link.message()).isEqualTo("正在准备微信扫码二维码，请稍候。");
    assertThat(link.qrLink()).isEmpty();
    verify(wechatBindService, never()).startBind(eq(instance), eq(true), eq("wx_existing"), any());

    executor.runNext();

    assertThat(saved.get().getStatus()).isEqualTo("waiting_scan");
    assertThat(saved.get().getQrLink()).isEqualTo("https://liteapp.weixin.qq.com/q/refresh");
    verify(wechatBindService).startBind(eq(instance), eq(true), eq("wx_existing"), any());
  }

  @Test
  void adminCanRevokeLink() {
    WechatBindLinkEntity stored = existingLink("token_revoke", "inst_1");
    stored.setStatus("waiting_scan");
    stored.setQrPayload("data:image/png;base64,abc");
    stored.setQrLink("https://qr.example.test");
    when(linkMapper.findByToken("token_revoke")).thenReturn(stored);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance("inst_1", "实例一", "running"));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    PublicWechatBindLink link = service.revokeLink("token_revoke", "https://admin.example.test");

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).update(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("revoked");
    assertThat(captor.getValue().getQrPayload()).isEmpty();
    assertThat(link.status()).isEqualTo("revoked");
    assertThat(link.statusLabel()).isEqualTo("已失效");
    assertThat(link.message()).isEqualTo("扫码链接已手动失效。");
  }

  private static AuthenticatedAdmin admin() {
    return new AuthenticatedAdmin(
        "admin_1",
        "admin@example.test",
        "管理员",
        false,
        "2026-06-18T00:00:00Z",
        "2026-06-18T00:00:00Z"
    );
  }

  private static WechatBindLinkEntity newLink(String token) {
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setToken(token);
    link.setMode("new");
    link.setCreatedByAdminId("admin_1");
    link.setCreatedAt("2026-06-18T00:00:00Z");
    link.setExpiresAt(Instant.now().plusSeconds(3600).toString());
    link.setUpdatedAt("2026-06-18T00:00:00Z");
    return link;
  }

  private static WechatBindLinkEntity existingLink(String token, String instanceId) {
    WechatBindLinkEntity link = newLink(token);
    link.setMode("existing");
    link.setPhone("13572873189");
    link.setInstanceId(instanceId);
    link.setTargetAccountId("wx_existing");
    return link;
  }

  private static WechatPairedAccountEntity pairedAccount(String accountId, String phone, String instanceId) {
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setAccountId(accountId);
    account.setPhone(phone);
    account.setInstanceId(instanceId);
    account.setWechatUserId("wechat-user");
    account.setRemark("");
    account.setBaseUrl("");
    account.setBoundAt("2026-06-18T00:00:00Z");
    account.setUpdatedAt("2026-06-18T00:00:00Z");
    return account;
  }

  private static InstanceEntity instance(String id, String name, String status) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId(id);
    instance.setName(name);
    instance.setStatus(status);
    return instance;
  }

  private static InstanceProvisioningEntity readyProvisioning(String instanceId) {
    InstanceProvisioningEntity provisioning = new InstanceProvisioningEntity();
    provisioning.setInstanceId(instanceId);
    provisioning.setStatus("ready");
    provisioning.setPercent(100);
    return provisioning;
  }

  private static InstanceProvisioningEntity runningProvisioning(String instanceId) {
    InstanceProvisioningEntity provisioning = readyProvisioning(instanceId);
    provisioning.setStatus("running");
    provisioning.setPercent(42);
    return provisioning;
  }

  private static InstancePaths testPaths() {
    Path base = Path.of("target", "wechat-bind-link-test");
    return new InstancePaths(base, base.resolve("home"), base.resolve("workspace"), base.resolve("logs"));
  }

  private static WechatBindService.BindStartResult startResult(String accountId, String qrLink) {
    return new WechatBindService.BindStartResult(accountId, null, "link", "", qrLink, "等待扫码");
  }

  private static WechatBindService.BindCompletion completion(
      String requestedAccountId,
      String accountId,
      String wechatUserId
  ) {
    return new WechatBindService.BindCompletion(
        requestedAccountId,
        accountId,
        accountId,
        wechatUserId,
        "https://wechat.example.test",
        "connected",
        false
    );
  }

  private static final class QueuedExecutor implements Executor {

    private final List<Runnable> tasks = new java.util.ArrayList<>();

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
