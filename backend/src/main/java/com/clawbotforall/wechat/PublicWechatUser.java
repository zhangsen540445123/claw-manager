package com.clawbotforall.wechat;

import java.util.List;

/** 用户中心统一用户及清理状态响应。 */
public record PublicWechatUser(
    String instanceId,
    String instanceName,
    String instanceStatus,
    String accountId,
    String phone,
    String wechatUserId,
    String agentId,
    String openVikingUserId,
    String remark,
    String baseUrl,
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
    String miniappLastUsedAt,
    String recordState,
    String cleanupOperationId,
    String cleanupStage,
    boolean retryable,
    String cleanupError,
    List<String> residueTypes
) {}
