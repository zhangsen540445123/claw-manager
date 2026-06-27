package com.clawbotforall.externalapi;

import com.clawbotforall.web.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalApiSettingsService {
  private static final String SETTINGS_ID = "default";

  private final ExternalApiSettingsMapper mapper;
  private final SecureRandom secureRandom = new SecureRandom();

  public ExternalApiSettingsService(ExternalApiSettingsMapper mapper) {
    this.mapper = mapper;
  }

  public PublicExternalApiSettings publicSettings() {
    return toPublic(effectiveSettings());
  }

  public ExternalApiSettingsEntity effectiveSettings() {
    ExternalApiSettingsEntity settings = mapper.find();
    if (settings == null) {
      settings = new ExternalApiSettingsEntity();
      settings.setId(SETTINGS_ID);
      settings.setApiKey("");
      settings.setEnabled(false);
      settings.setCreatedAt("");
      settings.setUpdatedAt("");
    }
    return settings;
  }

  @Transactional
  public PublicExternalApiSettings update(Map<String, Object> payload) {
    ExternalApiSettingsEntity current = effectiveSettings();
    ExternalApiSettingsEntity next = new ExternalApiSettingsEntity();
    String now = Instant.now().toString();
    next.setId(SETTINGS_ID);
    next.setCreatedAt(current.getCreatedAt() == null || current.getCreatedAt().isBlank() ? now : current.getCreatedAt());
    next.setUpdatedAt(now);
    next.setEnabled(payload != null && payload.containsKey("enabled")
        ? Boolean.TRUE.equals(payload.get("enabled"))
        : current.isEnabled());
    if (payload != null && Boolean.TRUE.equals(payload.get("regenerateApiKey"))) {
      next.setApiKey(generateApiKey());
    } else if (payload != null && payload.containsKey("apiKey")) {
      String apiKey = stringValue(payload.get("apiKey")).trim();
      next.setApiKey(apiKey.isBlank() ? defaultString(current.getApiKey()) : apiKey);
    } else {
      next.setApiKey(defaultString(current.getApiKey()));
    }
    mapper.upsert(next);
    return toPublic(next);
  }

  public void requireAuthorized(String authorization) {
    ExternalApiSettingsEntity settings = effectiveSettings();
    if (!settings.isEnabled()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "外部 API 接入未启用。");
    }
    String expected = defaultString(settings.getApiKey()).trim();
    if (expected.isBlank()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "外部 API Key 未配置。");
    }
    String token = defaultString(authorization).replaceFirst("(?i)^Bearer\\s+", "").trim();
    if (!expected.equals(token)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "外部 API Key 无效。");
    }
  }

  private PublicExternalApiSettings toPublic(ExternalApiSettingsEntity settings) {
    String apiKey = defaultString(settings.getApiKey());
    return new PublicExternalApiSettings(
        settings.isEnabled(),
        apiKey,
        !apiKey.isBlank(),
        preview(apiKey),
        defaultString(settings.getUpdatedAt())
    );
  }

  private String generateApiKey() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return "cm_api_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String preview(String value) {
    String normalized = defaultString(value);
    if (normalized.length() <= 12) {
      return normalized.isBlank() ? "" : normalized;
    }
    return normalized.substring(0, 8) + "..." + normalized.substring(normalized.length() - 4);
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
