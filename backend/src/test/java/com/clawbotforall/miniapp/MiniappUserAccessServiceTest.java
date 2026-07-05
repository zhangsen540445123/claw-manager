package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.externalapi.ExternalApiIdentityService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingIdentityService;
import com.clawbotforall.openviking.OpenVikingSettingsService;
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

  MiniappUserAccessService service;

  @BeforeEach
  void setUp() {
    service = new MiniappUserAccessService(
        bindingMapper,
        keyMapper,
        openVikingSettingsService,
        identityService,
        instanceService,
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
        new OpenVikingIdentityService(),
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

    MiniappChatRoute route = service.resolveChatRoute("Bearer cm_user_secret", "");

    assertThat(route.instance()).isSameAs(instance);
    assertThat(route.openid()).isEqualTo("openid_1");
    assertThat(route.openidHash()).isEqualTo("hash_1");
    assertThat(route.openvikingUserId()).isEqualTo("wx_hash_1");
    assertThat(route.senderId()).isEqualTo("miniapp:hash_1");
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
        new OpenVikingIdentityService(),
        Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC)
    );
    when(openVikingSettingsService.effectiveSettings()).thenReturn(settings());
    MiniappUserBindingEntity binding = binding("hash_1", "openid_1", "pending", "inst_1", "", "");
    binding.setCurrentBindToken("token_1");
    when(bindingMapper.findByOpenidHash("hash_1")).thenReturn(binding);
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setStatus("connected");
    link.setScannedWechatUserId("o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat");
    when(bindLinkMapper.findByToken("token_1")).thenReturn(link);

    MiniappUserBindingEntity result = service.reconcileBinding("hash_1");

    assertThat(result.getOpenvikingUserId()).isEqualTo("wx_a67b392317ec3e01e7ee1285528f8a2e");
    verify(bindingMapper).markConnected(
        "hash_1",
        "o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "2026-07-04T10:00:00Z",
        "2026-07-04T10:00:00Z"
    );
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
    return binding;
  }
}
