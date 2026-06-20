package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
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
import com.clawbotforall.instance.InstanceWechatBindingEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    verify(wechatBindService, never()).startBind(eq(instance), eq(false), any());
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
    assertThat(stored.getExpectedAccountId()).isEqualTo("wx_existing");
    assertThat(link.instanceName()).isEqualTo("老用户实例");
    assertThat(link.status()).isEqualTo("created");
    assertThat(link.qrLink()).isEmpty();
    assertThat(executor.size()).isEqualTo(1);
    verify(wechatBindService, never()).startBind(any(), eq(false), eq("wx_existing"));
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
    verify(wechatBindService, never()).startBind(any(), eq(false), any());
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
    when(accountSyncService.readRawAccountIds(idle)).thenReturn(List.of("wx_old"));
    when(aggregateMapper.findById("inst_idle")).thenReturn(idle);
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_idle"))).thenReturn(List.of(readyProvisioning("inst_idle")));
    when(wechatBindService.startBind(eq(idle), eq(false), any()))
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
    assertThat(saved.get().getSnapshotAccountIds()).isEqualTo("[\"wx_old\"]");
    assertThat(saved.get().getPendingAccountId()).startsWith("cmwx_wbl_");
    verify(wechatBindService).startBind(eq(idle), eq(false), any());
    verify(eventPublisher, atLeastOnce()).publishWechatBindLinkUpdated(eq(saved.get().getToken()), any(PublicWechatBindLink.class));
  }

  @Test
  void publicStatusCopiesWaitingScanQrFromInstanceBinding() {
    WechatBindLinkEntity stored = existingLink("token_3", "inst_1");
    stored.setStatus("starting");
    when(linkMapper.findByToken("token_3")).thenReturn(stored);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance("inst_1", "实例一", "running"));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));
    when(aggregateMapper.listWechatBindingByInstanceIds(List.of("inst_1")))
        .thenReturn(List.of(waitingScanBinding(Instant.now().plusSeconds(60).toString())));

    PublicWechatBindLink link = service.getPublicStatus("token_3", "https://admin.example.test");

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).update(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("waiting_scan");
    assertThat(link.status()).isEqualTo("waiting_scan");
    assertThat(link.qrMode()).isEqualTo("image");
    assertThat(link.qrPayload()).isEqualTo("data:image/png;base64,abc");
    assertThat(link.message()).isEqualTo("请使用微信扫描二维码完成绑定。");
  }

  @Test
  void publicStatusExpiresOldQrAndClearsPayload() {
    WechatBindLinkEntity stored = existingLink("token_4", "inst_1");
    stored.setStatus("waiting_scan");
    when(linkMapper.findByToken("token_4")).thenReturn(stored);
    when(aggregateMapper.findById("inst_1")).thenReturn(instance("inst_1", "实例一", "running"));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));
    when(aggregateMapper.listWechatBindingByInstanceIds(List.of("inst_1")))
        .thenReturn(List.of(waitingScanBinding(Instant.now().minusSeconds(1).toString())));

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
  void rejectsNewUserWhenWechatUserIdAlreadyBelongsToAnotherAccount() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = newLink("token_duplicate_wechat");
    stored.setStatus("starting");
    stored.setPhone("13900000001");
    stored.setInstanceId("inst_1");
    stored.setPendingAccountId("cmwx_duplicate_wechat");
    stored.setSnapshotAccountIds("[\"wx_old\"]");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_duplicate_wechat")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.listWechatBindingByInstanceIds(List.of("inst_1"))).thenReturn(List.of(connectedBinding("inst_1")));
    when(accountSyncService.readRawAccountIds(instance)).thenReturn(List.of("wx_old", "cmwx_duplicate_wechat"));
    when(accountSyncService.readRawAccounts(instance, Map.of())).thenReturn(List.of(rawAccount("cmwx_duplicate_wechat", "wechat-user")));
    when(aggregateMapper.findWechatAccountByAccountId("cmwx_duplicate_wechat")).thenReturn(null);
    when(aggregateMapper.findWechatAccountByWechatUserId("wechat-user"))
        .thenReturn(pairedAccount("wx_existing", "13800000000", "inst_other"));
    when(fileService.paths("inst_1")).thenReturn(testPaths());

    PublicWechatBindLink link = service.getPublicStatus("token_duplicate_wechat", "https://admin.example.test");

    assertThat(link.status()).isEqualTo("rejected");
    assertThat(link.message()).isEqualTo("该微信已绑定到其他手机号或实例，请联系管理员处理。");
    verify(mutationMapper, never()).insertWechatAccount(any());
    verify(accountSyncService).removeAccountStateFiles(any(InstancePaths.class), eq("cmwx_duplicate_wechat"));
  }

  @Test
  void rejectsNewUserWhenRawWechatUserIdIsMissing() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    WechatBindLinkEntity stored = newLink("token_missing_wechat_user");
    stored.setStatus("starting");
    stored.setPhone("13900000001");
    stored.setInstanceId("inst_1");
    stored.setPendingAccountId("cmwx_missing_wechat_user");
    stored.setSnapshotAccountIds("[\"wx_old\"]");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_missing_wechat_user")).thenAnswer(invocation -> saved.get());
    when(linkMapper.update(any())).thenAnswer(invocation -> {
      saved.set(invocation.getArgument(0));
      return 1;
    });
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(aggregateMapper.listWechatBindingByInstanceIds(List.of("inst_1"))).thenReturn(List.of(connectedBinding("inst_1")));
    when(accountSyncService.readRawAccountIds(instance)).thenReturn(List.of("wx_old", "cmwx_missing_wechat_user"));
    when(accountSyncService.readRawAccounts(instance, Map.of())).thenReturn(List.of(rawAccount("cmwx_missing_wechat_user", "")));
    when(fileService.paths("inst_1")).thenReturn(testPaths());

    PublicWechatBindLink link = service.getPublicStatus("token_missing_wechat_user", "https://admin.example.test");

    assertThat(link.status()).isEqualTo("rejected");
    assertThat(link.message()).isEqualTo("无法识别扫码微信用户，请重新扫码或联系管理员处理。");
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
    when(accountSyncService.readRawAccountIds(instance)).thenReturn(List.of());
    when(aggregateMapper.findById("inst_1")).thenReturn(instance);
    when(wechatBindService.startBind(eq(instance), eq(false), any()))
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
    assertThat(saved.get().getPendingAccountId()).startsWith("cmwx_wbl_");
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
    when(accountSyncService.readRawAccountIds(instance)).thenReturn(List.of("wx_existing"));
    when(wechatBindService.startBind(instance, true, "wx_existing"))
        .thenReturn(startResult("wx_existing", "https://liteapp.weixin.qq.com/q/refresh"));

    PublicWechatBindLink link = service.refreshQr("token_qr_expired", "https://admin.example.test");

    assertThat(link.status()).isEqualTo("starting");
    assertThat(link.message()).isEqualTo("正在准备微信扫码二维码，请稍候。");
    assertThat(link.qrLink()).isEmpty();
    verify(wechatBindService, never()).startBind(instance, true, "wx_existing");

    executor.runNext();

    assertThat(saved.get().getStatus()).isEqualTo("waiting_scan");
    assertThat(saved.get().getQrLink()).isEqualTo("https://liteapp.weixin.qq.com/q/refresh");
    verify(wechatBindService).startBind(instance, true, "wx_existing");
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
    link.setExpectedAccountId("wx_existing");
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

  private static InstanceWechatBindingEntity waitingScanBinding(String expiresAt) {
    InstanceWechatBindingEntity binding = new InstanceWechatBindingEntity();
    binding.setInstanceId("inst_1");
    binding.setStatus("waiting_scan");
    binding.setQrMode("image");
    binding.setQrPayload("data:image/png;base64,abc");
    binding.setQrLink("https://qr.example.test");
    binding.setQrExpiresAt(expiresAt);
    binding.setOutputSnippet("");
    return binding;
  }

  private static InstanceWechatBindingEntity connectedBinding(String instanceId) {
    InstanceWechatBindingEntity binding = new InstanceWechatBindingEntity();
    binding.setInstanceId(instanceId);
    binding.setStatus("connected");
    binding.setOutputSnippet("");
    return binding;
  }

  private static WechatPairedAccountEntity rawAccount(String accountId, String wechatUserId) {
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setAccountId(accountId);
    account.setWechatUserId(wechatUserId);
    account.setBaseUrl("https://wechat.example.test");
    account.setSavedAt("2026-06-18T00:00:00Z");
    return account;
  }

  private static InstancePaths testPaths() {
    Path base = Path.of("target", "wechat-bind-link-test");
    return new InstancePaths(base, base.resolve("home"), base.resolve("workspace"), base.resolve("logs"));
  }

  private static WechatBindService.BindStartResult startResult(String accountId, String qrLink) {
    return new WechatBindService.BindStartResult(accountId, null, "link", "", qrLink, "等待扫码");
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
