package com.clawbotforall.miniapp;

public record MiniappWechatBindingSummary(
    String instanceId,
    String wechatUserId,
    String openVikingUserId,
    String openid,
    String bindStatus,
    String keyPreview,
    boolean keyEnabled,
    String lastUsedAt
) {}
