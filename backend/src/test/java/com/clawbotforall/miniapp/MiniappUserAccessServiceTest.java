package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.externalapi.ExternalApiIdentityService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.useragent.UserAgentIdentityResult;
import com.clawbotforall.useragent.UserAgentIdentityService;
import com.clawbotforall.useragent.UserAgentProvisioningService;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.wechat.WechatBindLinkEntity;
import com.clawbotforall.wechat.WechatBindLinkMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MiniappUserAccessServiceTest {

  @Mock
  MiniappUserBindingMapper bindingMapper;

  @Mock
  MiniappUserKeyMapper keyMapper;

  @Mock
  OpenVikingSettingsService openVikingSettingsService;

  @Mock
  ExternalApiIdentityService identityService;

  @Mock
  MiniappInstanceService instanceService;

  @Mock
  WechatBindLinkMapper bindLinkMapper;

  @Mock
  UserAgentIdentityService userAgentIdentityService;

  @Mock
  UserAgentProvisioningService userAgentProvisioningService;

  MiniappUserAccessService service;

  @BeforeEach
  void setUp() {
    service = new MiniappUserAccessService(
        bindingMapper,
        keyMapper,
        openVikingSettingsService,
        identityService,
        instanceService,
        bindLinkMapper,
        userAgentIdentityService,
        userAgentProvisioningService,
        Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC)
    );
  }

  @Test
  void createsUserKeyOnlyAfterWechatBindingConnected() {
    when(openVikingSettingsService.effectiveSettings()).thenReturn(settings());
    when(identityService.resolve("openid_1", "salt_1")).thenReturn(apiIdentity("hash_1"));
    MiniappUserBindingEntity binding = binding("hash_1", "openid_1", "pending", "inst_1", "", "");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);

    assertThatThrownBy(() -> service.createOrGetUserKey("openid_1", false))
        .isInstanceOf(ApiException.class)
        .hasMessage("小程序用户尚未完成微信扫码绑定。");
  }

  @Test
  void rejectsUserKeyWhenWechatLinkIsOnlyInitializing() {
    service = new MiniappUserAccessService(
        bindingMapper,
        keyMapper,
        openVikingSettingsService,
        identityService,
        instanceService,
        bindLinkMapper,
        userAgentIdentityService,
        userAgentProvisioningService,
        Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC)
    );
    when(openVikingSettingsService.effectiveSettings()).thenReturn(settings());
    when(identityService.resolve("openid_1", "salt_1")).thenReturn(apiIdentity("hash_1"));
    MiniappUserBindingEntity binding = binding("hash_1", "openid_1", "waiting_scan", "inst_1", "", "");
    binding.setCurrentBindToken("token_1");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setStatus("initializing");
    link.setScannedWechatUserId("o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat");
    when(bindLinkMapper.findByToken("token_1")).thenReturn(link);
    assertThatThrownBy(() -> service.createOrGetUserKey("openid_1", false))
        .isInstanceOf(ApiException.class)
        .hasMessage("小程序用户尚未完成微信扫码绑定。");
    verify(bindingMapper, never()).markConnected(
        "hash_1",
        "o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat",
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "2026-07-04T10:00:00Z",
        "2026-07-04T10:00:00Z"
    );
  }

  @Test
  void returnsExistingKeyPreviewWithoutLeakingPlaintext() {
    when(openVikingSettingsService.effectiveSettings()).thenReturn(settings());
    when(identityService.resolve("openid_1", "salt_1")).thenReturn(apiIdentity("hash_1"));
    MiniappUserBindingEntity binding = binding("hash_1", "openid_1", "connected", "inst_1", "wx_user_1", "wx_hash_1");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);
    MiniappUserKeyEntity existing = new MiniappUserKeyEntity();
    existing.setOpenidHash("hash_1");
    existing.setUserKey("cm_user_secret");
    existing.setKeyPreview("cm_user_...cret");
    existing.setEnabled(true);
    when(keyMapper.findByOpenidHash("hash_1")).thenReturn(existing);

    MiniappUserKeyResult result = service.createOrGetUserKey("openid_1", false);

    assertThat(result.key()).isNull();
    assertThat(result.keyPreview()).isEqualTo("cm_user_...cret");
    assertThat(result.created()).isFalse();
    verify(keyMapper).updateLastUsed("hash_1", "2026-07-04T10:00:00Z");
  }

  @Test
  void resolvesBearerKeyToWxOpenVikingRoute() {
    MiniappUserKeyEntity key = new MiniappUserKeyEntity();
    key.setOpenidHash("hash_1");
    key.setOpenid("openid_1");
    key.setUserKey("cm_user_secret");
    key.setEnabled(true);
    when(keyMapper.findByUserKey("cm_user_secret")).thenReturn(key);
    MiniappUserBindingEntity binding = binding("hash_1", "openid_1", "connected", "inst_1", "wx_user_1", "wx_hash_1");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(instance);
    WechatBindLinkEntity link = connectedLink();
    when(bindLinkMapper.findByToken("token_1")).thenReturn(link);

    MiniappChatRoute route = service.resolveChatRoute("Bearer cm_user_secret", "");

    assertThat(route.instance()).isSameAs(instance);
    assertThat(route.openid()).isEqualTo("openid_1");
    assertThat(route.openidHash()).isEqualTo("hash_1");
    assertThat(route.agentId()).isEqualTo("user_11111111111111111111111111111111");
    assertThat(route.openvikingUserId()).isEqualTo("wx_hash_1");
    assertThat(route.senderId()).isEqualTo("miniapp:hash_1");
    verify(userAgentProvisioningService).ensure(
        "inst_1",
        "user_11111111111111111111111111111111",
        "wx_hash_1",
        "account_1",
        "wechat_peer_1"
    );
  }

  @Test
  void reconcilesWechatBindingWithDatabaseIdentitySalt() {
    service = new MiniappUserAccessService(
        bindingMapper,
        keyMapper,
        openVikingSettingsService,
        identityService,
        instanceService,
        bindLinkMapper,
        userAgentIdentityService,
        userAgentProvisioningService,
        Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC)
    );
    MiniappUserBindingEntity binding = binding("hash_1", "openid_1", "pending", "inst_1", "", "");
    binding.setCurrentBindToken("token_1");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setStatus("connected");
    link.setTargetAccountId("account_1");
    link.setScannedWechatUserId("o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat");
    when(bindLinkMapper.findByToken("token_1")).thenReturn(link);
    when(userAgentIdentityService.resolve("inst_1", "o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat"))
        .thenReturn(new UserAgentIdentityResult(
            "user_0123456789abcdef0123456789abcdef",
            "wx_a67b392317ec3e01e7ee1285528f8a2e",
            true
        ));

    MiniappUserBindingEntity result = service.reconcileBinding("hash_1");

    assertThat(result.getAgentId()).isEqualTo("user_0123456789abcdef0123456789abcdef");
    assertThat(result.getOpenvikingUserId()).isEqualTo("wx_a67b392317ec3e01e7ee1285528f8a2e");
    verify(bindingMapper).markConnected(
        "hash_1",
        "o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat",
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "2026-07-04T10:00:00Z",
        "2026-07-04T10:00:00Z"
    );
    InOrder provisioningOrder = inOrder(bindingMapper, userAgentProvisioningService);
    provisioningOrder.verify(bindingMapper).markConnected(
        "hash_1",
        "o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat",
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "2026-07-04T10:00:00Z",
        "2026-07-04T10:00:00Z"
    );
    provisioningOrder.verify(userAgentProvisioningService).ensureAsync(
        "inst_1",
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "account_1",
        "o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat"
    );
  }

  @Test
  void keepsBindingConnectedWhenAsynchronousProvisioningCannotBeScheduled() {
    MiniappUserBindingEntity binding = binding("hash_1", "openid_1", "pending", "inst_1", "", "");
    binding.setCurrentBindToken("token_1");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);
    WechatBindLinkEntity link = connectedLink();
    when(bindLinkMapper.findByToken("token_1")).thenReturn(link);
    when(userAgentIdentityService.resolve("inst_1", "wechat_peer_1"))
        .thenReturn(new UserAgentIdentityResult(
            "user_0123456789abcdef0123456789abcdef",
            "wx_a67b392317ec3e01e7ee1285528f8a2e",
            true
        ));
    doThrow(new IllegalStateException("executor stopped"))
        .when(userAgentProvisioningService).ensureAsync(
            "inst_1",
            "user_0123456789abcdef0123456789abcdef",
            "wx_a67b392317ec3e01e7ee1285528f8a2e",
            "account_1",
            "wechat_peer_1"
        );

    MiniappUserBindingEntity result = service.reconcileBinding("hash_1");

    assertThat(result.getBindStatus()).isEqualTo("connected");
    assertThat(result.getAgentId()).isEqualTo("user_0123456789abcdef0123456789abcdef");
  }

  @Test
  void blocksChatWhenSynchronousAgentProvisioningFails() {
    MiniappUserKeyEntity key = new MiniappUserKeyEntity();
    key.setOpenidHash("hash_1");
    key.setOpenid("openid_1");
    key.setEnabled(true);
    when(keyMapper.findByUserKey("cm_user_secret")).thenReturn(key);
    MiniappUserBindingEntity binding = binding(
        "hash_1", "openid_1", "connected", "inst_1", "wechat_peer_1", "wx_hash_1");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(instance);
    when(bindLinkMapper.findByToken("token_1")).thenReturn(connectedLink());
    doThrow(new IllegalStateException("queue failed"))
        .when(userAgentProvisioningService).ensure(
            "inst_1",
            "user_11111111111111111111111111111111",
            "wx_hash_1",
            "account_1",
            "wechat_peer_1"
        );

    assertThatThrownBy(() -> service.resolveChatRoute("Bearer cm_user_secret", ""))
        .isInstanceOf(ApiException.class)
        .hasMessage("用户 Agent 尚未准备完成，请稍后重试。");
    verify(keyMapper, never()).updateLastUsed("hash_1", "2026-07-04T10:00:00Z");
  }

  @Test
  void keepsCompleteConnectedIdentityWithoutRecomputingAfterSaltChanges() {
    MiniappUserBindingEntity binding = binding(
        "hash_1", "openid_1", "connected", "inst_1", "wechat_user_1", "wx_persisted");
    binding.setCurrentBindToken("token_1");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);

    MiniappUserBindingEntity result = service.reconcileBinding("hash_1");

    assertThat(result.getAgentId()).isEqualTo("user_11111111111111111111111111111111");
    assertThat(result.getOpenvikingUserId()).isEqualTo("wx_persisted");
    verify(bindLinkMapper, never()).findByToken("token_1");
    verify(userAgentIdentityService, never()).resolve("inst_1", "wechat_user_1");
  }

  @Test
  void keepsHistoricalConnectedBindingIncompleteWithoutLazyIdentityCreation() {
    MiniappUserBindingEntity binding = binding(
        "hash_1", "openid_1", "connected", "inst_1", "historical_wechat_user", "wx_historical");
    binding.setAgentId("");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);

    MiniappUserBindingEntity result = service.reconcileBinding("hash_1");

    assertThat(result).isSameAs(binding);
    assertThat(result.getAgentId()).isBlank();
    verify(bindLinkMapper, never()).findByToken("token_1");
    verify(userAgentIdentityService, never()).resolve(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString()
    );
    verify(bindingMapper, never()).markConnected(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString()
    );
  }

  @Test
  void doesNotCreateIdentityForHistoricalNonScanBindingState() {
    MiniappUserBindingEntity binding = binding(
        "hash_1", "openid_1", "legacy", "inst_1", "historical_wechat_user", "");
    binding.setAgentId("");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);

    MiniappUserBindingEntity result = service.reconcileBinding("hash_1");

    assertThat(result).isSameAs(binding);
    verify(bindLinkMapper, never()).findByToken("token_1");
    verify(userAgentIdentityService, never()).resolve(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString()
    );
  }

  @Test
  void rejectsChatRouteWhenConnectedBindingHasNoAgentId() {
    MiniappUserKeyEntity key = new MiniappUserKeyEntity();
    key.setOpenidHash("hash_1");
    key.setOpenid("openid_1");
    key.setEnabled(true);
    when(keyMapper.findByUserKey("cm_user_secret")).thenReturn(key);
    MiniappUserBindingEntity binding = binding(
        "hash_1", "openid_1", "connected", "inst_1", "wechat_user_1", "wx_persisted");
    binding.setAgentId("");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);

    assertThatThrownBy(() -> service.resolveChatRoute("Bearer cm_user_secret", ""))
        .isInstanceOf(ApiException.class)
        .hasMessage("小程序用户尚未完成微信扫码绑定。");
  }

  private static OpenVikingEffectiveSettings settings() {
    return new OpenVikingEffectiveSettings(
        "",
        true,
        "account_1",
        "salt_1",
        "",
        "",
        "",
        ""
    );
  }

  private static com.clawbotforall.externalapi.ExternalApiIdentity apiIdentity(String hash) {
    return new com.clawbotforall.externalapi.ExternalApiIdentity("openid_1", hash, "api_" + hash, "api:" + hash);
  }

  private static MiniappUserBindingEntity binding(
      String openidHash,
      String openid,
      String status,
      String instanceId,
      String wechatUserId,
      String openvikingUserId
  ) {
    MiniappUserBindingEntity binding = new MiniappUserBindingEntity();
    binding.setOpenidHash(openidHash);
    binding.setOpenid(openid);
    binding.setBindStatus(status);
    binding.setInstanceId(instanceId);
    binding.setWechatUserId(wechatUserId);
    binding.setOpenvikingUserId(openvikingUserId);
    binding.setCurrentBindToken("token_1");
    if (openvikingUserId != null && !openvikingUserId.isBlank()) {
      binding.setAgentId("user_11111111111111111111111111111111");
    }
    return binding;
  }

  private static WechatBindLinkEntity connectedLink() {
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setStatus("connected");
    link.setTargetAccountId("account_1");
    link.setScannedWechatUserId("wechat_peer_1");
    return link;
  }
}
