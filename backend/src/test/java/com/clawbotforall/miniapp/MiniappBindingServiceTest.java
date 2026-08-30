package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.externalapi.ExternalApiIdentity;
import com.clawbotforall.externalapi.ExternalApiIdentityService;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.wechat.PublicWechatBindLink;
import com.clawbotforall.wechat.WechatBindLinkEntity;
import com.clawbotforall.wechat.WechatBindLinkMapper;
import com.clawbotforall.wechat.WechatBindLinkService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MiniappBindingServiceTest {

  @Mock
  MiniappUserBindingMapper bindingMapper;

  @Mock
  MiniappInstanceService instanceService;

  @Mock
  WechatBindLinkService wechatBindLinkService;

  @Mock
  WechatBindLinkMapper wechatBindLinkMapper;

  @Mock
  InstanceAggregateMapper instanceMapper;

  @Mock
  OpenVikingSettingsService openVikingSettingsService;

  @Mock
  ExternalApiIdentityService identityService;

  @Mock
  MiniappUserAccessService userAccessService;

  MiniappBindingService service;

  @BeforeEach
  void setUp() {
    service = new MiniappBindingService(
        bindingMapper,
        instanceService,
        wechatBindLinkService,
        wechatBindLinkMapper,
        instanceMapper,
        openVikingSettingsService,
        identityService,
        userAccessService,
        Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC)
    );
  }

  @Test
  void connectedBindingWithoutAgentIdCannotCreateUserKey() {
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setToken("wbl_sensitive_token");
    link.setMiniappOpenidHash("openid_hash_1");
    when(wechatBindLinkMapper.findByToken("wbl_sensitive_token")).thenReturn(link);
    MiniappUserBindingEntity binding = new MiniappUserBindingEntity();
    binding.setOpenid("openid_1");
    binding.setBindStatus("connected");
    binding.setInstanceId("inst_1");
    binding.setOpenvikingUserId("wx_0123456789abcdef0123456789abcdef");
    binding.setAgentId("");
    when(userAccessService.reconcileBinding("openid_hash_1")).thenReturn(binding);
    when(wechatBindLinkService.getPublicStatus("wbl_sensitive_token", ""))
        .thenReturn(publicLink("wbl_sensitive_token", "connected"));

    MiniappBindLinkResult result = service.getBindLink("wbl_sensitive_token", "");

    assertThat(result.canCreateUserKey()).isFalse();
  }

  @Test
  void creatingAnotherQrLinkDoesNotDowngradeAConnectedBinding() {
    when(openVikingSettingsService.effectiveSettings()).thenReturn(
        new com.clawbotforall.openviking.OpenVikingEffectiveSettings(
            "", true, "account_1", "salt_1", "", "", "", ""));
    when(identityService.resolve("openid_1", "salt_1"))
        .thenReturn(new ExternalApiIdentity("openid_1", "openid_hash_1", "api_1", "api:openid_hash_1"));
    MiniappUserBindingEntity binding = new MiniappUserBindingEntity();
    binding.setOpenidHash("openid_hash_1");
    binding.setOpenid("openid_1");
    binding.setInstanceId("inst_1");
    binding.setWechatUserId("wechat_user_1");
    binding.setAgentId("user_11111111111111111111111111111111");
    binding.setOpenvikingUserId("wx_11111111111111111111111111111111");
    binding.setBindStatus("connected");
    when(bindingMapper.findByOpenidHashForUpdate("openid_hash_1")).thenReturn(binding);
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setAccountId("account_1");
    when(instanceMapper.findWechatAccountByWechatUserId("wechat_user_1")).thenReturn(account);
    when(wechatBindLinkService.createMiniappLink(
        "openid_hash_1", "inst_1", "account_1", "https://miniapp.example.test"))
        .thenReturn(publicLink("token_2", "created"));

    MiniappBindLinkResult result = service.createWechatBindLink(
        "openid_1", "https://miniapp.example.test");

    verify(bindingMapper).updateBindTokenPreservingStatus(
        "openid_hash_1", "token_2", "2026-07-19T12:00:00Z");
    verify(bindingMapper, never()).updateBindToken(
        "openid_hash_1", "token_2", "2026-07-19T12:00:00Z");
    assertThat(result.canCreateUserKey()).isTrue();
    assertThat(binding.getBindStatus()).isEqualTo("connected");
  }

  private static PublicWechatBindLink publicLink(String token, String status) {
    return new PublicWechatBindLink(
        token,
        "new",
        status,
        "",
        "inst_1",
        "实例 1",
        "link",
        "",
        "https://qr.example.test",
        "2026-07-19T12:05:00Z",
        false,
        "",
        "2026-07-19T12:10:00Z",
        null,
        "2026-07-19T12:00:00Z",
        "2026-07-19T12:00:00Z",
        "已连接",
        "新绑定",
        "https://bind.example.test"
    );
  }
}
