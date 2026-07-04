package com.clawbotforall.miniapp;

import com.clawbotforall.instance.InstanceEntity;

public record MiniappChatRoute(
    InstanceEntity instance,
    String openid,
    String openidHash,
    String openvikingUserId,
    String senderId
) {}
