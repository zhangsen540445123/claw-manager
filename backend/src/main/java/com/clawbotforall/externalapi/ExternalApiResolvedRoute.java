package com.clawbotforall.externalapi;

import com.clawbotforall.instance.InstanceEntity;

public record ExternalApiResolvedRoute(
    InstanceEntity instance,
    String openidHash,
    String openvikingUserId,
    String senderId
) {}
