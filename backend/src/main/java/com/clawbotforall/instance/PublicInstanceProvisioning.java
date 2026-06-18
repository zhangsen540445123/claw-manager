package com.clawbotforall.instance;

/**
 * 实例创建进度和 Gateway 就绪状态的 API 安全响应模型。
 */
public record PublicInstanceProvisioning(
    String status,
    int percent,
    String stage,
    String message,
    String gatewayStartedAt,
    String updatedAt
) {}
