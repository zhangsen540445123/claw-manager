package com.clawbotforall.miniapp;

public record MiniappUserKeyResult(
    String openid,
    String key,
    String keyPreview,
    String openVikingUserId,
    String instanceId,
    boolean created
) {}
