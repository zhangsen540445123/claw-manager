package com.clawbotforall.image;

import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImageGenerationSettingsService implements ImageGenerationSettingsProvider {
  private final ImageGenerationSettingsMapper mapper;

  public ImageGenerationSettingsService(ImageGenerationSettingsMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public ImageGenerationSettings current() {
    ImageGenerationSettings settings = mapper.find();
    return settings == null ? ImageGenerationSettings.disabled() : settings;
  }

  public PublicImageGenerationSettings getPublicSettings() {
    return PublicImageGenerationSettings.from(current());
  }

  @Transactional
  public PublicImageGenerationSettings save(Map<String, Object> payload) {
    ImageGenerationSettings previous = current();
    boolean enabled = bool(payload, "enabled", previous.enabled());
    String providerId = text(payload, "providerId", previous.providerId());
    String modelId = text(payload, "modelId", previous.modelId());
    String apiMode = text(payload, "apiMode", previous.apiMode());
    String baseUrl = text(payload, "baseUrl", previous.baseUrl());
    String apiKey = text(payload, "apiKey", previous.apiKey());
    String providerConfig = text(payload, "providerConfig", previous.providerConfig());
    int timeoutMs = integer(payload, "timeoutMs", previous.timeoutMs() <= 0 ? 180_000 : previous.timeoutMs());
    if (enabled && (providerId.isBlank() || modelId.isBlank() || apiKey.isBlank())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "启用图片生成时 Provider、模型和 API Key 不能为空。");
    }
    if (timeoutMs < 10_000 || timeoutMs > 600_000) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "图片生成超时必须在 10000 到 600000 毫秒之间。");
    }
    ImageGenerationSettings settings = new ImageGenerationSettings(
        enabled, providerId, modelId, apiMode, baseUrl, apiKey,
        providerConfig.isBlank() ? "{}" : providerConfig, timeoutMs, Instant.now().toString()
    );
    mapper.upsert(settings);
    return PublicImageGenerationSettings.from(settings);
  }

  private static String text(Map<String, Object> payload, String key, String fallback) {
    if (payload == null || !payload.containsKey(key) || payload.get(key) == null) return fallback == null ? "" : fallback;
    return String.valueOf(payload.get(key)).trim();
  }

  private static boolean bool(Map<String, Object> payload, String key, boolean fallback) {
    if (payload == null || !payload.containsKey(key)) return fallback;
    Object value = payload.get(key);
    return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
  }

  private static int integer(Map<String, Object> payload, String key, int fallback) {
    if (payload == null || !payload.containsKey(key)) return fallback;
    try { return Integer.parseInt(String.valueOf(payload.get(key))); }
    catch (NumberFormatException error) { throw new ApiException(HttpStatus.BAD_REQUEST, "图片生成超时格式不正确。"); }
  }
}
