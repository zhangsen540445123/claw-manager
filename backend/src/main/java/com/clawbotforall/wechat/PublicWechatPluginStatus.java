package com.clawbotforall.wechat;

/**
 * 管理员后台展示的微信插件安装状态。
 */
public record PublicWechatPluginStatus(
    boolean installed,
    String currentVersion,
    String latestVersion,
    boolean upgradable,
    String status,
    String message,
    String outputSnippet,
    String updatedAt
) {}
