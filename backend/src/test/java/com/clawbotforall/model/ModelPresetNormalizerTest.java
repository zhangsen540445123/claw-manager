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
        "apiKey", " sk-test "
    ), null);

    assertThat(model.providerKey()).isEqualTo("custom-provider");
    assertThat(model.providerId()).isEqualTo("openai");
    assertThat(model.modelId()).isEqualTo("gpt-5.5");
    assertThat(model.apiMode()).isEqualTo("openai-responses");
    assertThat(model.baseUrl()).isEqualTo("https://api.example.test/v1");
    assertThat(model.apiKey()).isEqualTo("sk-test");
  }

  @Test
  void keepsExistingApiKeyWhenBlankApiKeyIsSubmittedForSameProvider() {
    ModelPresetEntity existing = new ModelPresetEntity();
    existing.setProviderKey("custom-provider");
    existing.setProviderId("openai");
    existing.setModelId("gpt-5.5");
    existing.setApiMode("openai-responses");
    existing.setApiKey("sk-existing");

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("providerKey", "custom-provider");
    payload.put("providerId", "openai");
    payload.put("modelId", "gpt-5.5");
    payload.put("apiMode", "openai-responses");
    payload.put("apiKey", "   ");

    NormalizedModelSelection model = normalizer.normalizePayload(payload, existing);

    assertThat(model.apiKey()).isEqualTo("sk-existing");
  }

  @Test
  void rejectsIncompleteCustomProviderPayload() {
    assertThatThrownBy(() -> normalizer.normalizePayload(Map.of(
        "providerKey", "custom-provider",
        "apiMode", "openai-responses"
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
    return preset;
  }
}
