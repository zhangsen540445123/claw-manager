package com.clawbotforall.instance;

/**
 * 模型认证流程状态的 API 安全响应模型。
 */
public record PublicInstanceModelAuth(
    String status,
    String updatedAt,
    String message,
    String outputSnippet,
    String authUrl,
    String promptLabel,
    boolean needsInput
) {}
