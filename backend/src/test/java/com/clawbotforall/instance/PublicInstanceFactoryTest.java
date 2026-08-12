package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.miniapp.MiniappWechatBindingSummary;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingIdentityService;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicInstanceFactoryTest {

  private final OpenVikingSettingsService openVikingSettingsService = mock(OpenVikingSettingsService.class);
  private final PublicInstanceFactory factory = new PublicInstanceFactory(
      new ObjectMapper(),
      properties(),
      openVikingSettingsService,
      new OpenVikingIdentityService()
  );

  PublicInstanceFactoryTest() {
    when(openVikingSettingsService.effectiveSettings()).thenReturn(new OpenVikingEffectiveSettings(
        "http://openviking:1933",
        true,
        "claw-manager",
        "display-salt",
        "npm:@claw-manager/openviking-openclaw-plugin@2026.6.37",
        "root-key",
        "broker-token",
        "http://claw-manager-api:8080"
    ));
  }

  private static ClawbotProperties properties() {
    return new ClawbotProperties(
          new ClawbotProperties.Paths("data"),
          new ClawbotProperties.Admin("", "平台管理员", ""),
          new ClawbotProperties.Security("clawbot_session", 14),
          new ClawbotProperties.Runtime(
              "runner:latest",
              600_000,
              "1.0",
              "1g",
              600_000,
              120_000,
              1_800_000,
              10_000,
              5_000,
              List.of()
          )
    );
  }

  @Test
  void exposesFrontendCompatibleInstanceShape() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("测试实例");
    instance.setSlug("test");
    instance.setStatus("running");
    instance.setPort(19001);
    instance.setDashboardUrl("http://127.0.0.1:19001/");
    instance.setContainerName("clawbot-openclaw-inst_1");
    instance.setGatewayToken("token-1");
    instance.setPluginsAllow("[]");
    instance.setPluginsEntries("{}");
    instance.setCreatedAt("2026-06-14T00:00:00Z");
    instance.setUpdatedAt("2026-06-14T00:01:00Z");

    InstanceModelEntity model = new InstanceModelEntity();
    model.setInstanceId("inst_1");
    model.setPresetId("preset_1");
    model.setProviderKey("custom-provider");
    model.setProviderId("openai");
    model.setModelId("gpt-5.5");
    model.setApiMode("openai-responses");
    model.setAuthType("custom_gateway");
    model.setAuthProviderId("openai");
    model.setAuthMethodId("");
    model.setBaseUrl("https://example.com/v1");
    model.setApiKey("sk-1234567890");
    model.setExtra("{\"note\":\"demo\"}");
    model.setContextWindow(200_000);
    model.setMaxTokens(20_000);

    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setInstanceId("inst_1");
    account.setAccountId("wx_1");
    account.setPhone("13572873189");
    account.setWechatUserId("wx_user");
    account.setRemark("战神");

    WechatAccountChannelEntity channel = new WechatAccountChannelEntity();
    channel.setInstanceId("inst_1");
    channel.setAccountId("wx_1");
    channel.setWechatUserId("wx_user");
    channel.setStatus("ready");
    channel.setMessage("微信通道已激活。");
    channel.setUpdatedAt("2026-06-14T00:02:00Z");

    PublicInstance publicInstance = factory.from(
        instance,
        List.of(model),
        null,
        null,
        List.of(account),
        List.of(channel),
        List.of(new MiniappWechatBindingSummary(
            "inst_1",
            "wx_user",
            "",
            "miniapp-openid-001",
            "connected",
            "cm_user_abcd...wxyz",
            true,
            "2026-07-04T10:00:00Z"
        )),
        new MockHttpServletRequest()
    );

    assertThat(publicInstance.dashboardUrl())
        .isEqualTo("/proxy/inst_1/?gatewayUrl=ws%3A%2F%2Flocalhost%2Fproxy%2Finst_1#token=token-1");
    assertThat(publicInstance.provisioning().status()).isEqualTo("ready");
    assertThat(publicInstance.model().presetId()).isEqualTo("preset_1");
    assertThat(publicInstance.model().apiKeyMasked()).isEqualTo("sk-1••••7890");
    assertThat(publicInstance.model().extra()).containsEntry("note", "demo");
    assertThat(publicInstance.model().contextWindow()).isEqualTo(200_000);
    assertThat(publicInstance.model().maxTokens()).isEqualTo(20_000);
    assertThat(publicInstance.models()).hasSize(1);
    assertThat(publicInstance.modelChain()).hasSize(1);
    assertThat(publicInstance.plugins().get("allow")).asList().isEmpty();
    assertThat(publicInstance.wechatBinding().pairedAccounts()).hasSize(1);
    assertThat(publicInstance.wechatBinding().miniappBindingCount()).isEqualTo(1);
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().remark()).isEqualTo("战神");
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().openVikingUserId()).startsWith("wx_");
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().miniappOpenid()).isEqualTo("miniapp-openid-001");
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().miniappBindStatus()).isEqualTo("connected");
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().miniappKeyPreview()).isEqualTo("cm_user_abcd...wxyz");
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().miniappKeyEnabled()).isTrue();
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().miniappLastUsedAt()).isEqualTo("2026-07-04T10:00:00Z");
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().channelStatus()).isEqualTo("ready");
    assertThat(publicInstance.wechatBinding().status()).isEqualTo("ready");
  }

  @Test
  void exposesMiniappBindingCountEvenWhenNoWechatAccountIsBound() {
    PublicInstance publicInstance = factory.from(
        baseInstance(),
        List.of(),
        null,
        null,
        List.of(),
        List.of(),
        List.of(new MiniappWechatBindingSummary(
            "inst_1",
            "",
            "wx_orphan_openviking_user",
            "miniapp-openid-002",
            "connected",
            "cm_user_abcd...wxyz",
            true,
            "2026-07-04T10:00:00Z"
        )),
        new MockHttpServletRequest()
    );

    assertThat(publicInstance.wechatBinding().pairedAccounts()).isEmpty();
    assertThat(publicInstance.wechatBinding().miniappBindingCount()).isEqualTo(1);
  }

  @Test
  void dashboardUrlUsesForwardedOriginForGatewayUrl() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "admin.example.test");

    PublicInstance publicInstance = factory.from(
        baseInstance(),
        List.of(),
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        request
    );

    assertThat(publicInstance.dashboardUrl())
        .isEqualTo("/proxy/inst_1/?gatewayUrl=wss%3A%2F%2Fadmin.example.test%2Fproxy%2Finst_1#token=token-1");
  }

  @Test
  void dashboardUrlFallsBackToRelativeProxyUrlWithoutRequest() {
    PublicInstance publicInstance = factory.from(
        baseInstance(),
        List.of(),
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        null
    );

    assertThat(publicInstance.dashboardUrl()).isEqualTo("/proxy/inst_1/#token=token-1");
  }

  @Test
  void derivesWechatSummaryFromAccountChannelStatus() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("测试实例");
    instance.setSlug("test");
    instance.setStatus("running");
    instance.setPort(19001);
    instance.setDashboardUrl("http://127.0.0.1:19001/");
    instance.setContainerName("clawbot-openclaw-inst_1");
    instance.setGatewayToken("token-1");
    instance.setPluginsAllow("[]");
    instance.setPluginsEntries("{}");
    instance.setCreatedAt("2026-06-14T00:00:00Z");
    instance.setUpdatedAt("2026-06-14T00:01:00Z");

    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setInstanceId("inst_1");
    account.setAccountId("wx_1");
    account.setPhone("13572873189");
    account.setWechatUserId("wx_user");

    WechatAccountChannelEntity channel = new WechatAccountChannelEntity();
    channel.setInstanceId("inst_1");
    channel.setAccountId("wx_1");
    channel.setWechatUserId("wx_user");
    channel.setStatus("starting");
    channel.setMessage("正在启动微信通道。");
    channel.setUpdatedAt(Instant.now().toString());

    PublicInstance publicInstance = factory.from(
        instance,
        List.of(),
        null,
        null,
        List.of(account),
        List.of(channel),
        List.of(),
        new MockHttpServletRequest()
    );

    assertThat(publicInstance.wechatBinding().status()).isEqualTo("starting");
    assertThat(publicInstance.wechatBinding().runtimeStatus()).isEqualTo("pending");
    assertThat(publicInstance.wechatBinding().qrPayload()).isEmpty();
    assertThat(publicInstance.wechatBinding().qrLink()).isEmpty();
  }

  @Test
  void returnsIdleWechatSummaryWhenNoAccountIsBound() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("测试实例");
    instance.setSlug("test");
    instance.setStatus("running");
    instance.setPort(19001);
    instance.setDashboardUrl("http://127.0.0.1:19001/");
    instance.setContainerName("clawbot-openclaw-inst_1");
    instance.setGatewayToken("token-1");
    instance.setPluginsAllow("[]");
    instance.setPluginsEntries("{}");
    instance.setCreatedAt("2026-06-14T00:00:00Z");
    instance.setUpdatedAt("2026-06-14T00:01:00Z");

    PublicInstance publicInstance = factory.from(
        instance,
        List.of(),
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        new MockHttpServletRequest()
    );

    assertThat(publicInstance.wechatBinding().status()).isEqualTo("idle");
    assertThat(publicInstance.wechatBinding().qrExpired()).isFalse();
    assertThat(publicInstance.wechatBinding().qrPayload()).isEmpty();
    assertThat(publicInstance.wechatBinding().qrLink()).isEmpty();
  }

  private static InstanceEntity baseInstance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("测试实例");
    instance.setSlug("test");
    instance.setStatus("running");
    instance.setPort(19001);
    instance.setDashboardUrl("http://127.0.0.1:19001/");
    instance.setContainerName("clawbot-openclaw-inst_1");
    instance.setGatewayToken("token-1");
    instance.setPluginsAllow("[]");
    instance.setPluginsEntries("{}");
    instance.setCreatedAt("2026-06-14T00:00:00Z");
    instance.setUpdatedAt("2026-06-14T00:01:00Z");
    return instance;
  }
}
