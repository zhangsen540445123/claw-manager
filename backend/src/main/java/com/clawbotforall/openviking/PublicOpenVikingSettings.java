package com.clawbotforall.openviking;

public record PublicOpenVikingSettings(
    String baseUrl,
    boolean trustedModeEnabled,
    String accountId,
    String pluginPackage,
    boolean rootApiKeyConfigured,
    String rootApiKeyFingerprint,
    boolean saltConfigured,
    String saltSource,
    String saltFingerprint,
    String updatedAt
) {}
