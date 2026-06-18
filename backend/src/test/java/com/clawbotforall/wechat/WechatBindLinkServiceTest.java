package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.instance.InstanceWechatBindingEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
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

  WechatBindLinkService service;

  @BeforeEach
  void setUp() {
    service = new WechatBindLinkService(
        linkMapper,
        aggregateMapper,
        mutationMapper,
        wechatBindService,
        accountSyncService,
        fileService,
        new ClawbotProperties(null, null, null, null),
        new ObjectMapper()
    );
  }

  @Test
  void createsNewUserLinkThatRequiresPhone() {
    InstanceEntity instance = instance("inst_1", "实例一", "running");
    when(aggregateMapper.listAll()).thenReturn(List.of(instance));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    PublicWechatBindLink link = service.createLink(
        admin(),
        new WechatBindLinkService.CreateBindLinkRequest("new", null),
        "https://admin.example.test"
    );

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).insert(captor.capture());
    WechatBindLinkEntity stored = captor.getValue();
    assertThat(stored.getToken()).startsWith("wbl_");
    assertThat(stored.getMode()).isEqualTo("new");
    assertThat(stored.getStatus()).isEqualTo("phone_required");
    assertThat(stored.getCreatedByAdminId()).isEqualTo("admin_1");
    assertThat(link.status()).isEqualTo("phone_required");
    assertThat(link.message()).isEqualTo("请先填写手机号获取微信扫码二维码。");
    assertThat(link.bindLink()).startsWith("https://admin.example.test/bind/wbl_");
  }

  @Test
  void rejectsNewUserLinkWhenNoReadyRunningInstanceExists() {
    when(aggregateMapper.listAll()).thenReturn(List.of(instance("inst_1", "实例一", "stopped")));
    when(aggregateMapper.listProvisioningByInstanceIds(List.of("inst_1"))).thenReturn(List.of(readyProvisioning("inst_1")));

    assertThatThrownBy(() -> service.createLink(
        admin(),
        new WechatBindLinkService.CreateBindLinkRequest("new", null),
        "https://admin.example.test"
    ))
        .hasMessage("当前暂无可用 OpenClaw 实例，请先启动并等待实例就绪。");
  }

  @Test
  void createsExistingUserLinkForOriginalInstance() {
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
  }

  @Test
  void rejectsNewPhoneWhenItAlreadyHasABinding() {
    WechatBindLinkEntity stored = newLink("token_1");
    stored.setStatus("phone_required");
    when(linkMapper.findByToken("token_1")).thenReturn(stored);
    when(aggregateMapper.findWechatAccountByPhone("13572873189"))
        .thenReturn(pairedAccount("wx_existing", "13572873189", "inst_1"));

    PublicWechatBindLink link = service.submitPhone("token_1", "13572873189", "https://admin.example.test");

    ArgumentCaptor<WechatBindLinkEntity> captor = ArgumentCaptor.forClass(WechatBindLinkEntity.class);
    verify(linkMapper).update(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("rejected");
    assertThat(link.status()).isEqualTo("rejected");
    assertThat(link.message()).isEqualTo("该手机号已绑定，请联系管理员获取老用户扫码链接。");
  }

  @Test
  void assignsNewPhoneToLeastLoadedReadyRunningInstanceAndStartsQr() {
    WechatBindLinkEntity stored = newLink("token_2");
    stored.setStatus("phone_required");
    AtomicReference<WechatBindLinkEntity> saved = new AtomicReference<>(stored);
    when(linkMapper.findByToken("token_2")).thenAnswer(invocation -> saved.get());
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

    PublicWechatBindLink link = service.submitPhone("token_2", "13572873189", "https://admin.example.test");

    assertThat(link.status()).isEqualTo("starting");
    assertThat(link.phone()).isEqualTo("13572873189");
    assertThat(link.instanceId()).isEqualTo("inst_idle");
    assertThat(saved.get().getSnapshotAccountIds()).isEqualTo("[\"wx_old\"]");
    verify(wechatBindService).startBind(idle, false);
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
    assertThat(link.message()).isEqualTo("微信已绑定成功，OpenClaw 正在重启微信通道，通常需要 1-3 分钟，请稍后再使用。");
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
}
