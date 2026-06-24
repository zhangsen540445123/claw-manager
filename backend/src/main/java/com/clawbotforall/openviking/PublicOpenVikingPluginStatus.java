package com.clawbotforall.openviking;

/**
 * 管理员后台展示的 OpenViking 插件安装状态。
 */
public record PublicOpenVikingPluginStatus(
    boolean installed,
    String currentVersion,
    String latestVersion,
    boolean upgradable,
    String status,
    String message,
    String outputSnippet,
    String updatedAt
) {}
