package com.clawbotforall.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelProviderServiceTest {

  private final ModelProviderService service = new ModelProviderService(new ObjectMapper());

  @Test
  void loadsFrontendCompatibleProviderDefinitions() {
    assertThat(service.listProviders()).hasSize(20);

    ModelProviderDefinition customProvider = service.findByKey("custom-provider");
    assertThat(customProvider).isNotNull();
    assertThat(customProvider.label()).isEqualTo("自定义 Provider");
    assertThat(customProvider.authType()).isEqualTo("custom_gateway");
    assertThat(customProvider.apiMode()).isEqualTo("openai-completions");
    assertThat(customProvider.fields()).extracting(field -> field.get("name"))
        .contains("providerId", "modelId", "apiMode", "baseUrl", "apiKey");
  }
}
