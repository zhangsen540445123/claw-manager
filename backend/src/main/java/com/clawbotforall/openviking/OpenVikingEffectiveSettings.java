package com.clawbotforall.openviking;

public record OpenVikingEffectiveSettings(
    String baseUrl,
    boolean trustedModeEnabled,
    String accountId,
    String identityHashSecret,
    String pluginPackage,
    String rootApiKey,
    String brokerToken,
    String internalBaseUrl
) {}
