package com.clawbotforall.instance;

import java.util.List;

/**
 * 微信绑定和配对账号状态的 API 安全响应模型。
 */
public record PublicWechatBinding(
    String status,
    String updatedAt,
    String qrExpiresAt,
    boolean qrExpired,
    String qrMode,
    String qrPayload,
    String qrLink,
    String outputSnippet,
    List<PublicWechatPairedAccount> pairedAccounts,
    boolean runtimeReady,
    String runtimeStatus,
    String runtimeMessage,
    String runtimeUpdatedAt
) {}
