package com.clawbotforall.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelPresetNormalizerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ModelProviderService providerService = new ModelProviderService(objectMapper);
  private final ModelPresetNormalizer normalizer = new ModelPresetNormalizer(providerService, objectMapper);

  @Test
  void normalizesCustomProviderLikeNodeImplementation() {
    NormalizedModelSelection model = normalizer.normalizePayload(Map.of(
        "providerKey", "custom-provider",
        "providerId", " OpenAI ",
        "modelId", " gpt-5.5 ",
        "apiMode", " openai-responses ",
        "baseUrl", " https://api.example.test/v1/// ",
        "apiKey", " sk-test ",
        "contextWindow", 1_000_000,
        "maxTokens", 128_000
    ), null);

    assertThat(model.providerKey()).isEqualTo("custom-provider");
    assertThat(model.providerId()).isEqualTo("openai");
    assertThat(model.modelId()).isEqualTo("gpt-5.5");
    assertThat(model.apiMode()).isEqualTo("openai-responses");
    assertThat(model.baseUrl()).isEqualTo("https://api.example.test/v1");
    assertThat(model.apiKey()).isEqualTo("sk-test");
    assertThat(model.contextWindow()).isEqualTo(1_000_000);
    assertThat(model.maxTokens()).isEqualTo(128_000);
  }

  @Test
  void requiresTokenLimitsWhenCreatingPreset() {
    assertThatThrownBy(() -> normalizer.normalizePayload(Map.of(
        "providerKey", "custom-provider",
        "providerId", "openai",
        "modelId", "gpt-5.5",
        "apiMode", "openai-responses",
        "baseUrl", "https://api.example.test/v1",
        "apiKey", "sk-test"
    ), null))
        .isInstanceOf(ApiException.class)
        .hasMessage("模型预设必须填写 Context Window 和 Max Tokens。");
  }

  @Test
  void rejectsInvalidTokenLimits() {
    assertThatThrownBy(() -> normalizer.normalizePayload(Map.of(
        "providerKey", "custom-provider",
        "providerId", "openai",
        "modelId", "gpt-5.5",
        "apiMode", "openai-responses",
        "baseUrl", "https://api.example.test/v1",
        "apiKey", "sk-test",
        "contextWindow", 0,
        "maxTokens", "abc"
    ), null))
        .isInstanceOf(ApiException.class)
        .hasMessage("Context Window 和 Max Tokens 必须是正整数。");
  }

  @Test
  void keepsExistingApiKeyWhenBlankApiKeyIsSubmittedForSameProvider() {
    ModelPresetEntity existing = new ModelPresetEntity();
    existing.setProviderKey("custom-provider");
    existing.setProviderId("openai");
    existing.setModelId("gpt-5.5");
    existing.setApiMode("openai-responses");
    existing.setApiKey("sk-existing");
    existing.setContextWindow(200_000);
    existing.setMaxTokens(20_000);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("providerKey", "custom-provider");
    payload.put("providerId", "openai");
    payload.put("modelId", "gpt-5.5");
    payload.put("apiMode", "openai-responses");
    payload.put("apiKey", "   ");

    NormalizedModelSelection model = normalizer.normalizePayload(payload, existing);

    assertThat(model.apiKey()).isEqualTo("sk-existing");
    assertThat(model.contextWindow()).isEqualTo(200_000);
    assertThat(model.maxTokens()).isEqualTo(20_000);
  }

  @Test
  void rejectsIncompleteCustomProviderPayload() {
    assertThatThrownBy(() -> normalizer.normalizePayload(Map.of(
        "providerKey", "custom-provider",
        "apiMode", "openai-responses",
        "contextWindow", 1_000_000,
        "maxTokens", 128_000
    ), null))
        .isInstanceOf(ApiException.class)
        .hasMessage("模型配置不完整，请至少填写 provider、model 和 API 模式。");
  }

  @Test
  void determinesConfiguredStateByProviderAuthType() {
    ModelPresetEntity apiKeyPreset = preset("openai-api", "openai", "gpt-5.4", "openai-responses");
    assertThat(normalizer.isConfigured(apiKeyPreset)).isFalse();
    apiKeyPreset.setApiKey("sk-test");
    assertThat(normalizer.isConfigured(apiKeyPreset)).isTrue();

    ModelPresetEntity customPreset = preset("custom-provider", "openai", "gpt-5.5", "openai-responses");
    assertThat(normalizer.isConfigured(customPreset)).isFalse();
    customPreset.setBaseUrl("https://example.com/v1");
    assertThat(normalizer.isConfigured(customPreset)).isTrue();
  }

  private static ModelPresetEntity preset(
      String providerKey,
      String providerId,
      String modelId,
      String apiMode
  ) {
    ModelPresetEntity preset = new ModelPresetEntity();
    preset.setProviderKey(providerKey);
    preset.setProviderId(providerId);
    preset.setModelId(modelId);
    preset.setApiMode(apiMode);
    preset.setContextWindow(1_000_000);
    preset.setMaxTokens(128_000);
    return preset;
  }
}
