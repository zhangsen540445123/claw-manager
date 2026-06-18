package com.clawbotforall.model;

import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 将模型预设和手动模型载荷规范化为运行时模型选择。
 */
@Component
public class ModelPresetNormalizer {

  private final ModelProviderService modelProviderService;
  private final ObjectMapper objectMapper;

  public ModelPresetNormalizer(
      ModelProviderService modelProviderService,
      ObjectMapper objectMapper
  ) {
    this.modelProviderService = modelProviderService;
    this.objectMapper = objectMapper;
  }

  /**
   * 将手动载荷规范化为运行时模型选择。
   */

  public NormalizedModelSelection normalizePayload(
      Map<String, Object> payload,
      ModelPresetEntity existingPreset
  ) {
    NormalizedModelSelection existing = existingPreset == null ? null : normalizeEntity(existingPreset);
    return normalizePayloadWithExistingSelection(payload, existing);
  }

  /**
   * 规范化载荷，并在字段缺失时保留已有模型字段。
   */

  public NormalizedModelSelection normalizePayloadWithExistingSelection(
      Map<String, Object> payload,
      NormalizedModelSelection existing
  ) {
    Map<String, Object> patch = payload == null ? Map.of() : payload;
    String requestedKey = firstNonBlank(
        trimString(patch.get("providerKey")),
        existing == null ? "" : existing.providerKey(),
        guessProviderKey(patch)
    );
    ModelProviderDefinition definition = providerDefinition(requestedKey);
    NormalizedModelSelection next = normalizeModelCore(definition, patch, existing);

    if (next.providerId().isBlank() || next.modelId().isBlank() || next.apiMode().isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "模型配置不完整，请至少填写 provider、model 和 API 模式。");
    }

    return next;
  }

  /**
   * 将已存储模型预设规范化为运行时模型选择。
   */

  public NormalizedModelSelection normalizePreset(ModelPresetEntity preset) {
    return normalizeEntity(preset);
  }

  /**
   * 拒绝无法在运行时使用的规范化模型选择。
   */

  public void validateRuntimeUsable(NormalizedModelSelection model, String presetName) {
    if (model == null) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "模型配置不完整，请至少填写 provider、model 和 API 模式。");
    }
    ModelProviderDefinition definition = providerDefinition(model.providerKey());
    if ("api_key".equals(definition.authType()) && trimSecret(model.apiKey()).isBlank()) {
      String name = sanitizeName(presetName);
      if (name.isBlank()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "当前模型需要 API Key。");
      }
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "模型预设“" + name + "”尚未配置 API Key，请先由管理员补全。"
      );
    }
  }

  public boolean isConfigured(ModelPresetEntity preset) {
    if (preset == null) {
      return false;
    }

    NormalizedModelSelection normalized = normalizeEntity(preset);
    ModelProviderDefinition definition = providerDefinition(normalized.providerKey());
    if ("api_key".equals(definition.authType())) {
      return !trimSecret(normalized.apiKey()).isBlank();
    }
    if ("custom_gateway".equals(definition.authType())) {
      return !trimString(normalized.baseUrl()).isBlank() || !trimSecret(normalized.apiKey()).isBlank();
    }
    return true;
  }

  /**
   * 规范化展示名称并限制长度。
   */

  public String sanitizeName(Object value) {
    String normalized = trimString(value).replaceAll("\\s+", " ");
    return normalized.substring(0, Math.min(60, normalized.length()));
  }

  /**
   * 将前端提交的布尔开关值解析为布尔类型。
   */

  public boolean parseBooleanFlag(Object value, boolean fallback) {
    if (value == null || trimString(value).isBlank()) {
      return fallback;
    }
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    String normalized = trimString(value).toLowerCase(Locale.ROOT);
    return normalized.equals("1")
        || normalized.equals("true")
        || normalized.equals("yes")
        || normalized.equals("on");
  }

  private NormalizedModelSelection normalizeEntity(ModelPresetEntity preset) {
    String providerKey = firstNonBlank(
        trimString(preset.getProviderKey()),
        guessProviderKey(Map.of(
            "providerId", defaultString(preset.getProviderId()),
            "apiMode", defaultString(preset.getApiMode()),
            "baseUrl", defaultString(preset.getBaseUrl()),
            "authMethodId", defaultString(preset.getAuthMethodId())
        ))
    );
    ModelProviderDefinition definition = providerDefinition(providerKey);
    String providerId = trimString(firstNonBlank(preset.getProviderId(), definition.providerId()))
        .toLowerCase(Locale.ROOT);
    String modelId = trimString(firstNonBlank(preset.getModelId(), definition.defaultModelId()));
    String apiMode = trimString(firstNonBlank(preset.getApiMode(), definition.apiMode(), "openai-responses"));
    String baseUrl = normalizeBaseUrl(firstNonBlank(preset.getBaseUrl(), definition.defaultBaseUrl()));
    String apiKey = trimSecret(preset.getApiKey());
    return new NormalizedModelSelection(
        definition.key(),
        providerId,
        modelId,
        apiMode,
        defaultString(definition.authType()),
        firstNonBlank(definition.authProviderId(), providerId),
        defaultString(definition.authMethodId()),
        baseUrl,
        apiKey,
        preset.getProviderConfig(),
        preset.getExtra()
    );
  }

  private NormalizedModelSelection normalizeModelCore(
      ModelProviderDefinition definition,
      Map<String, Object> patch,
      NormalizedModelSelection existing
  ) {
    String providerId = trimString(selectValue(
        patch,
        "providerId",
        existing == null ? null : existing.providerId(),
        definition.providerId()
    )).toLowerCase(Locale.ROOT);
    String modelId = trimString(selectValue(
        patch,
        "modelId",
        existing == null ? null : existing.modelId(),
        definition.defaultModelId()
    ));
    String apiMode = trimString(selectValue(
        patch,
        "apiMode",
        existing == null ? null : existing.apiMode(),
        firstNonBlank(definition.apiMode(), "openai-responses")
    ));
    String baseUrl = normalizeBaseUrl(selectValue(
        patch,
        "baseUrl",
        existing == null ? null : existing.baseUrl(),
        definition.defaultBaseUrl()
    ));

    boolean apiKeyPresent = patch.containsKey("apiKey");
    Object rawApiKey = patch.get("apiKey");
    boolean shouldKeepExistingApiKey = apiKeyPresent
        && trimSecret(rawApiKey).isBlank()
        && existing != null
        && existing.providerKey().equals(definition.key());
    String apiKey = trimSecret(shouldKeepExistingApiKey
        ? existing.apiKey()
        : (apiKeyPresent ? rawApiKey : (existing == null ? "" : existing.apiKey())));

    String providerConfigJson = patch.containsKey("providerConfig")
        ? writeJsonOrNull(patch.get("providerConfig"))
        : (existing == null ? null : existing.providerConfigJson());
    String extraJson = patch.containsKey("extra")
        ? normalizeExtraJson(patch.get("extra"))
        : (existing == null ? "{}" : normalizeExistingExtra(existing.extraJson()));

    return new NormalizedModelSelection(
        definition.key(),
        providerId,
        modelId,
        apiMode,
        firstNonBlank(definition.authType(), "custom_gateway"),
        firstNonBlank(definition.authProviderId(), providerId),
        defaultString(definition.authMethodId()),
        baseUrl,
        apiKey,
        providerConfigJson,
        extraJson
    );
  }

  private ModelProviderDefinition providerDefinition(String key) {
    ModelProviderDefinition definition = modelProviderService.findByKey(key);
    return definition == null ? modelProviderService.customProvider() : definition;
  }

  private String guessProviderKey(Map<String, Object> model) {
    String providerId = trimString(model.get("providerId")).toLowerCase(Locale.ROOT);
    String apiMode = trimString(model.get("apiMode")).toLowerCase(Locale.ROOT);
    String baseUrl = normalizeBaseUrl(model.get("baseUrl")).toLowerCase(Locale.ROOT);
    String authMethodId = trimString(model.get("authMethodId")).toLowerCase(Locale.ROOT);

    if (providerId.equals("openai-codex")) return "openai-codex";
    if (providerId.equals("google-gemini-cli")) return "google-gemini-cli";
    if (providerId.equals("qwen-portal")) return "qwen-oauth";
    if (providerId.equals("github-copilot")) return "github-copilot";
    if (providerId.equals("chutes") && authMethodId.equals("oauth")) return "chutes-oauth";
    if (providerId.equals("chutes")) return "chutes-api";
    if (providerId.equals("minimax-portal")) {
      if (authMethodId.equals("oauth-cn") || baseUrl.contains("minimaxi.com")) return "minimax-cn-oauth";
      return "minimax-global-oauth";
    }
    if (providerId.equals("moonshot") && baseUrl.contains("moonshot.cn")) return "moonshot-api-cn";
    if (providerId.equals("moonshot")) return "moonshot-api";
    if (providerId.equals("google")) return "google-api";
    if (providerId.equals("openai")) return "openai-api";
    if (providerId.equals("zai") && authMethodId.equals("coding-global")) return "zai-coding-global";
    if (providerId.equals("zai") && authMethodId.equals("coding-cn")) return "zai-coding-cn";
    if (providerId.equals("zai")) return "zai-api";
    if (providerId.equals("anthropic") && authMethodId.equals("setup-token")) return "anthropic-setup-token";
    if (providerId.equals("anthropic") || apiMode.equals("anthropic-messages")) return "anthropic-api";
    if (providerId.equals("minimax") && baseUrl.contains("minimaxi.com")) return "minimax-cn-api";
    if (providerId.equals("minimax")) return "minimax-global-api";
    return "custom-provider";
  }

  private Object selectValue(
      Map<String, Object> patch,
      String key,
      Object existingValue,
      Object defaultValue
  ) {
    if (patch.containsKey(key)) {
      return patch.get(key);
    }
    if (existingValue != null) {
      return existingValue;
    }
    return defaultValue;
  }

  private String normalizeExistingExtra(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return "{}";
    }
    try {
      return normalizeExtraJson(objectMapper.readValue(rawJson, Object.class));
    } catch (JsonProcessingException error) {
      return "{}";
    }
  }

  private String normalizeExtraJson(Object rawExtra) {
    if (!(rawExtra instanceof Map<?, ?> rawMap)) {
      return "{}";
    }

    Map<String, Object> normalized = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      String key = String.valueOf(entry.getKey());
      Object value = entry.getValue();
      if (value instanceof String stringValue) {
        value = stringValue.trim();
      }
      if (value == null || (value instanceof String stringValue && stringValue.isBlank())) {
        continue;
      }
      normalized.put(key, value);
    }
    return writeJson(normalized);
  }

  private String writeJsonOrNull(Object value) {
    return value == null ? null : writeJson(value);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "模型配置不是合法 JSON。");
    }
  }

  private static String normalizeBaseUrl(Object value) {
    return trimString(value).replaceAll("/+$", "");
  }

  private static String trimSecret(Object value) {
    return trimString(value);
  }

  private static String trimString(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
