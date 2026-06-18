package com.clawbotforall.instance;

import java.util.Map;

/**
 * 隐藏凭证后的实例模型 API 安全响应模型。
 */
public record PublicInstanceModel(
    String presetId,
    String providerKey,
    String providerId,
    String modelId,
    String apiMode,
    String authType,
    String authProviderId,
    String authMethodId,
    String baseUrl,
    String apiKeyMasked,
    Map<String, Object> extra
) {}
