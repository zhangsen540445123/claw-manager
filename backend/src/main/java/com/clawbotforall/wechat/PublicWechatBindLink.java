package com.clawbotforall.wechat;

/**
 * 微信扫码绑定链接的公开响应模型。
 */
public record PublicWechatBindLink(
    String token,
    String mode,
    String status,
    String phone,
    String instanceId,
    String instanceName,
    String qrMode,
    String qrPayload,
    String qrLink,
    String qrExpiresAt,
    boolean qrExpired,
    String message,
    String expiresAt,
    String completedAt,
    String createdAt,
    String updatedAt,
    String statusLabel,
    String modeLabel,
    String bindLink
) {}
