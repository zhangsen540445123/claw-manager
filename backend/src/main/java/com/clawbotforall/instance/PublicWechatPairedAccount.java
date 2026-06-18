package com.clawbotforall.instance;

/**
 * 已绑定微信账号的 API 安全响应模型。
 */
public record PublicWechatPairedAccount(
    String accountId,
    String phone,
    String wechatUserId,
    String remark,
    String baseUrl,
    String savedAt,
    String boundAt,
    String updatedAt
) {}
