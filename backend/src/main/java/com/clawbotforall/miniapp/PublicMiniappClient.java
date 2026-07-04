package com.clawbotforall.miniapp;

public record PublicMiniappClient(
    String appId,
    String appSecret,
    String appSecretPreview,
    boolean enabled,
    String createdAt,
    String updatedAt,
    boolean created
) {}
