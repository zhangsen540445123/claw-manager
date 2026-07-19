package com.clawbotforall.useragent;

public record UserAgentIdentityResult(
    String agentId,
    String openVikingUserId,
    boolean created
) {}
