package com.clawbotforall.wechat;

public record WechatBindConnectedEvent(
    String instanceId,
    String accountId,
    String scannedWechatUserId,
    String miniappOpenidHash
) {}
