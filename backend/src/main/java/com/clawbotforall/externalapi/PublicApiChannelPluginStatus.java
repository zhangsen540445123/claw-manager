package com.clawbotforall.externalapi;

public record PublicApiChannelPluginStatus(
    boolean installed,
    String currentVersion,
    String latestVersion,
    boolean upgradable,
    String status,
    String message,
    String outputSnippet,
    String updatedAt
) {}
