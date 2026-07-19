package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.clawbotforall.externalapi.ExternalApiIdentityService;
import com.clawbotforall.instance.InstanceAggregateMapper;
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
