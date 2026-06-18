package com.clawbotforall.model;

/**
 * 可存储或写入 OpenClaw 的规范化模型配置。
 */
public record NormalizedModelSelection(
    String providerKey,
    String providerId,
    String modelId,
    String apiMode,
    String authType,
    String authProviderId,
    String authMethodId,
    String baseUrl,
    String apiKey,
    String providerConfigJson,
    String extraJson
) {}
