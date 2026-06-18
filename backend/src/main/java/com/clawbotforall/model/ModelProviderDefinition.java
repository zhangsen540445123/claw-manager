package com.clawbotforall.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * 受支持 Provider 变体及其 UI 字段定义。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelProviderDefinition(
    String key,
    String label,
    String providerId,
    String authType,
    String authProviderId,
    String authMethodId,
    String apiMode,
    String defaultModelId,
    String defaultBaseUrl,
    boolean supportsInteractiveAuth,
    boolean forceRemoteOAuth,
    List<Map<String, Object>> fields
) {

  public ModelProviderDefinition {
    key = defaultString(key);
    label = defaultString(label);
    providerId = defaultString(providerId);
    authType = defaultString(authType);
    authProviderId = defaultString(authProviderId);
    authMethodId = defaultString(authMethodId);
    apiMode = defaultString(apiMode);
    defaultModelId = defaultString(defaultModelId);
    defaultBaseUrl = defaultString(defaultBaseUrl);
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
