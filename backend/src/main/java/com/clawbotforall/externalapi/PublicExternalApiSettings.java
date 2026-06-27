package com.clawbotforall.externalapi;

public record PublicExternalApiSettings(
    boolean enabled,
    String apiKey,
    boolean apiKeyConfigured,
    String apiKeyPreview,
    String updatedAt
) {}
