package com.clawbotforall.model;

import java.util.List;

/**
 * 隐藏敏感状态后的模型预设 API 安全响应模型。
 */
public record PublicModelPreset(
    String id,
    String name,
    boolean isDefault,
    boolean isConfigured,
    String providerKey,
    String providerId,
    String modelId,
    String apiMode,
    String authType,
    String authProviderId,
    String authMethodId,
    String baseUrl,
    boolean hasBaseUrl,
    boolean hasApiKey,
    int contextWindow,
    int maxTokens,
    String createdAt,
    List<String> fallbackPresetIds
) {

  public static PublicModelPreset from(
      ModelPresetEntity preset,
      boolean isConfigured,
      List<String> fallbackPresetIds
  ) {
    String baseUrl = defaultString(preset.getBaseUrl());
    String apiKey = defaultString(preset.getApiKey());
    List<String> fallbacks = fallbackPresetIds == null ? List.of() : List.copyOf(fallbackPresetIds);
    return new PublicModelPreset(
        preset.getId(),
        preset.getName(),
        preset.isDefault(),
        isConfigured,
        defaultString(preset.getProviderKey()),
        defaultString(preset.getProviderId()),
        defaultString(preset.getModelId()),
        defaultString(preset.getApiMode()),
        defaultString(preset.getAuthType()),
        defaultString(preset.getAuthProviderId()),
        defaultString(preset.getAuthMethodId()),
        baseUrl,
        !baseUrl.trim().isEmpty(),
        !apiKey.trim().isEmpty(),
        preset.getContextWindow(),
        preset.getMaxTokens(),
        preset.getCreatedAt(),
        fallbacks
    );
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
