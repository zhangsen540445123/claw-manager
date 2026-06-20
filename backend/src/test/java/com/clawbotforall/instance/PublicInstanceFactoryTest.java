package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.config.ClawbotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicInstanceFactoryTest {

  private final PublicInstanceFactory factory = new PublicInstanceFactory(
      new ObjectMapper(),
      new ClawbotProperties(
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
              1_000_000,
              128_000,
              List.of()
          )
      )
  );

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

    InstanceWechatBindingEntity binding = new InstanceWechatBindingEntity();
    binding.setInstanceId("inst_1");
    binding.setStatus("connected");
    binding.setRuntimeReady(true);
    binding.setRuntimeStatus("ready");

    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    account.setInstanceId("inst_1");
    account.setAccountId("wx_1");
    account.setPhone("13572873189");
    account.setWechatUserId("wx_user");
    account.setRemark("战神");

    PublicInstance publicInstance = factory.from(
        instance,
        List.of(model),
        null,
        null,
        binding,
        List.of(account),
        new MockHttpServletRequest()
    );

    assertThat(publicInstance.dashboardUrl())
        .isEqualTo("/proxy/inst_1/?gatewayUrl=ws%3A%2F%2Flocalhost%2Fproxy%2Finst_1#token=token-1");
    assertThat(publicInstance.provisioning().status()).isEqualTo("ready");
    assertThat(publicInstance.model().presetId()).isEqualTo("preset_1");
    assertThat(publicInstance.model().apiKeyMasked()).isEqualTo("sk-1••••7890");
    assertThat(publicInstance.model().extra()).containsEntry("note", "demo");
    assertThat(publicInstance.models()).hasSize(1);
    assertThat(publicInstance.modelChain()).hasSize(1);
    assertThat(publicInstance.plugins().get("allow")).asList().isEmpty();
    assertThat(publicInstance.wechatBinding().pairedAccounts()).hasSize(1);
    assertThat(publicInstance.wechatBinding().pairedAccounts().getFirst().remark()).isEqualTo("战神");
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
        null,
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
        null,
        List.of(),
        null
    );

    assertThat(publicInstance.dashboardUrl()).isEqualTo("/proxy/inst_1/#token=token-1");
  }

  @Test
  void marksWaitingWechatQrAsExpiredAfterTtl() {
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

    InstanceWechatBindingEntity binding = new InstanceWechatBindingEntity();
    binding.setInstanceId("inst_1");
    binding.setStatus("waiting_scan");
    binding.setUpdatedAt(Instant.now().toString());
    binding.setQrExpiresAt(Instant.now().minusSeconds(1).toString());
    binding.setQrMode("image");
    binding.setQrPayload("data:image/png;base64,abc");
    binding.setQrLink("https://example.com/qr");

    PublicInstance publicInstance = factory.from(
        instance,
        List.of(),
        null,
        null,
        binding,
        List.of(),
        new MockHttpServletRequest()
    );

    assertThat(publicInstance.wechatBinding().status()).isEqualTo("expired");
    assertThat(publicInstance.wechatBinding().qrExpired()).isTrue();
    assertThat(publicInstance.wechatBinding().qrPayload()).isEmpty();
    assertThat(publicInstance.wechatBinding().qrLink()).isEmpty();
  }

  @Test
  void keepsWaitingWechatQrWhenPersistedExpiryIsStillFuture() {
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

    InstanceWechatBindingEntity binding = new InstanceWechatBindingEntity();
    binding.setInstanceId("inst_1");
    binding.setStatus("waiting_scan");
    binding.setUpdatedAt(Instant.now().minusSeconds(600).toString());
    binding.setQrExpiresAt(Instant.now().plusSeconds(60).toString());
    binding.setQrMode("image");
    binding.setQrPayload("data:image/png;base64,abc");
    binding.setQrLink("https://example.com/qr");

    PublicInstance publicInstance = factory.from(
        instance,
        List.of(),
        null,
        null,
        binding,
        List.of(),
        new MockHttpServletRequest()
    );

    assertThat(publicInstance.wechatBinding().status()).isEqualTo("waiting_scan");
    assertThat(publicInstance.wechatBinding().qrExpired()).isFalse();
    assertThat(publicInstance.wechatBinding().qrPayload()).isEqualTo("data:image/png;base64,abc");
    assertThat(publicInstance.wechatBinding().qrLink()).isEqualTo("https://example.com/qr");
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
