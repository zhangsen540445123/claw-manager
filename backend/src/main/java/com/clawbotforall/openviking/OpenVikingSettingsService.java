package com.clawbotforall.openviking;

import com.clawbotforall.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理所有 OpenClaw 实例共享的 OpenViking 预置配置。
 */
@Service
public class OpenVikingSettingsService {

  public static final String GLOBAL_ID = "global";
  public static final String DEFAULT_PLUGIN_PACKAGE = "npm:@claw-manager/openviking-openclaw-plugin@2026.6.36";
  private static final String DEFAULT_INTERNAL_BASE_URL = "http://claw-manager-api:8080";

  private final OpenVikingSettingsMapper mapper;
  private final OpenVikingIdentityService identityService;
  private final OpenVikingBrokerTokenService brokerTokenService;

  public OpenVikingSettingsService(
      OpenVikingSettingsMapper mapper,
      OpenVikingIdentityService identityService,
      OpenVikingBrokerTokenService brokerTokenService
  ) {
    this.mapper = mapper;
    this.identityService = identityService;
    this.brokerTokenService = brokerTokenService;
  }

  @Transactional(readOnly = true)
  public PublicOpenVikingSettings publicSettings() {
    return toPublic(effectiveEntity());
  }

  @Transactional
  public PublicOpenVikingSettings updateSettings(Map<String, Object> payload) {
    OpenVikingSettingsEntity current = effectiveEntity();
    String now = Instant.now().toString();
    OpenVikingSettingsEntity next = new OpenVikingSettingsEntity();
    next.setId(GLOBAL_ID);
    next.setBaseUrl(normalizeBaseUrl(value(payload, "baseUrl", current.getBaseUrl())));
    next.setTrustedModeEnabled(booleanValue(payload == null ? null : payload.get("trustedModeEnabled"), current.isTrustedModeEnabled()));
    next.setAccountId(defaultIfBlank(value(payload, "accountId", current.getAccountId()), "claw-manager"));
    next.setPluginPackage(defaultIfBlank(value(payload, "pluginPackage", current.getPluginPackage()), DEFAULT_PLUGIN_PACKAGE));
    next.setIdentitySalt(resolveIdentitySalt(payload, current.getIdentitySalt()));
    next.setRootApiKey(resolveRootApiKey(payload, current.getRootApiKey()));
    next.setCreatedAt(current.getCreatedAt() == null || current.getCreatedAt().isBlank() ? now : current.getCreatedAt());
    next.setUpdatedAt(now);
    mapper.upsert(next);
    return toPublic(next);
  }

  @Transactional(readOnly = true)
  public OpenVikingEffectiveSettings effectiveSettings() {
    OpenVikingSettingsEntity entity = effectiveEntity();
    return new OpenVikingEffectiveSettings(
        entity.getBaseUrl(),
        entity.isTrustedModeEnabled(),
        entity.getAccountId(),
        effectiveIdentitySalt(entity),
        entity.getPluginPackage(),
        defaultString(entity.getRootApiKey()),
        brokerTokenService.brokerToken(),
        DEFAULT_INTERNAL_BASE_URL
    );
  }

  private PublicOpenVikingSettings toPublic(OpenVikingSettingsEntity entity) {
    String salt = effectiveIdentitySalt(entity);
    return new PublicOpenVikingSettings(
        entity.getBaseUrl(),
        entity.isTrustedModeEnabled(),
        entity.getAccountId(),
        entity.getPluginPackage(),
        hasText(entity.getRootApiKey()),
        fingerprint(entity.getRootApiKey()),
        !salt.isBlank(),
        hasText(entity.getIdentitySalt()) ? "configured" : "generated",
        fingerprint(salt),
        entity.getUpdatedAt()
    );
  }

  private OpenVikingSettingsEntity effectiveEntity() {
    OpenVikingSettingsEntity persisted = mapper.findGlobal();
    if (persisted != null) {
      return normalizeEntity(persisted);
    }
    OpenVikingSettingsEntity defaults = new OpenVikingSettingsEntity();
    defaults.setId(GLOBAL_ID);
    defaults.setBaseUrl("");
    defaults.setTrustedModeEnabled(true);
    defaults.setAccountId("claw-manager");
    defaults.setPluginPackage(DEFAULT_PLUGIN_PACKAGE);
    defaults.setIdentitySalt("");
    defaults.setRootApiKey("");
    defaults.setCreatedAt("");
    defaults.setUpdatedAt("");
    return defaults;
  }

  private static OpenVikingSettingsEntity normalizeEntity(OpenVikingSettingsEntity entity) {
    entity.setBaseUrl(normalizeBaseUrl(entity.getBaseUrl()));
    entity.setAccountId(defaultIfBlank(entity.getAccountId(), "claw-manager"));
    entity.setPluginPackage(defaultIfBlank(entity.getPluginPackage(), DEFAULT_PLUGIN_PACKAGE));
    entity.setIdentitySalt(defaultString(entity.getIdentitySalt()));
    entity.setRootApiKey(defaultString(entity.getRootApiKey()));
    return entity;
  }

  private String effectiveIdentitySalt(OpenVikingSettingsEntity entity) {
    String configured = defaultString(entity.getIdentitySalt());
    return configured.isBlank() ? identityService.identityHashSecret() : configured;
  }

  private String resolveIdentitySalt(Map<String, Object> payload, String currentIdentitySalt) {
    if (payload == null || !payload.containsKey("identitySalt")) {
      return defaultIfBlank(currentIdentitySalt, identityService.identityHashSecret());
    }
    Object value = payload.get("identitySalt");
    String normalized = value == null ? "" : String.valueOf(value).trim();
    return normalized.isBlank()
        ? defaultIfBlank(currentIdentitySalt, identityService.identityHashSecret())
        : normalized;
  }

  private static String resolveRootApiKey(Map<String, Object> payload, String currentRootApiKey) {
    if (payload != null && Boolean.TRUE.equals(payload.get("clearRootApiKey"))) {
      return "";
    }
    if (payload == null || !payload.containsKey("rootApiKey")) {
      return defaultString(currentRootApiKey);
    }
    Object value = payload.get("rootApiKey");
    String normalized = value == null ? "" : String.valueOf(value).trim();
    return normalized.isBlank() ? defaultString(currentRootApiKey) : normalized;
  }

  private static String value(Map<String, Object> payload, String key, String fallback) {
    if (payload == null || !payload.containsKey(key)) {
      return fallback == null ? "" : fallback;
    }
    Object value = payload.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  private static boolean booleanValue(Object value, boolean fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean flag) {
      return flag;
    }
    String normalized = String.valueOf(value).trim();
    if ("true".equalsIgnoreCase(normalized)) {
      return true;
    }
    if ("false".equalsIgnoreCase(normalized)) {
      return false;
    }
    throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking trusted mode 配置必须是布尔值。");
  }

  private static String normalizeBaseUrl(String value) {
    String normalized = value == null ? "" : value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static String defaultIfBlank(String value, String fallback) {
    String normalized = value == null ? "" : value.trim();
    return normalized.isBlank() ? fallback : normalized;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String defaultString(String value) {
    return value == null ? "" : value.trim();
  }

  private static String fingerprint(String secret) {
    if (secret == null || secret.isBlank()) {
      return "";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    } catch (Exception error) {
      throw new IllegalStateException("OpenViking secret fingerprint 计算失败。", error);
    }
  }
}
