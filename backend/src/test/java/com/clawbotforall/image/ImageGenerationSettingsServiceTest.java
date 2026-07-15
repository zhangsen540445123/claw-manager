package com.clawbotforall.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ImageGenerationSettingsServiceTest {

  @Test
  void returnsMaskedSettingsAndPreservesSecretWhenPayloadOmitsIt() {
    ImageGenerationSettingsMapper mapper = Mockito.mock(ImageGenerationSettingsMapper.class);
    ImageGenerationSettings stored = new ImageGenerationSettings(
        true, "openai", "gpt-image-2", "openai-images", "", "sk-secret", "{}", 180_000, "old"
    );
    Mockito.when(mapper.find()).thenReturn(stored);
    ImageGenerationSettingsService service = new ImageGenerationSettingsService(mapper);

    PublicImageGenerationSettings publicSettings = service.getPublicSettings();
    service.save(Map.of(
        "enabled", true,
        "providerId", "openai",
        "modelId", "gpt-image-2",
        "apiMode", "openai-images",
        "timeoutMs", 120000
    ));

    assertThat(publicSettings.apiKeyPreview()).startsWith("sk-").endsWith("cret");
    Mockito.verify(mapper).upsert(Mockito.argThat(value -> "sk-secret".equals(value.apiKey())));
  }
}
