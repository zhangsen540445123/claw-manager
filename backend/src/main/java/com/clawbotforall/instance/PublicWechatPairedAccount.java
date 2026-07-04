package com.clawbotforall.instance;

/**
 * 已绑定微信账号的 API 安全响应模型。
 */
public record PublicWechatPairedAccount(
    String accountId,
    String phone,
    String wechatUserId,
    String openVikingUserId,
    String remark,
    String baseUrl,
    String savedAt,
    String boundAt,
    String updatedAt,
    String channelStatus,
    String channelMessage,
    String channelUpdatedAt,
    String lastStartedAt,
    String lastErrorAt,
    String miniappOpenid,
    String miniappBindStatus,
    String miniappKeyPreview,
    boolean miniappKeyEnabled,
    String miniappLastUsedAt
) {}
