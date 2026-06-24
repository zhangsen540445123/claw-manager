package com.clawbotforall.openviking;

public record PublicOpenVikingSettings(
    String baseUrl,
    boolean trustedModeEnabled,
    String accountId,
    String pluginPackage,
    boolean rootApiKeyConfigured,
    String rootApiKeyFingerprint,
    boolean identitySecretConfigured,
    String identitySecretSource,
    String identitySecretFingerprint,
    String updatedAt
) {}
