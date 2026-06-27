package com.clawbotforall.externalapi;

public record ExternalApiIdentity(
    String openid,
    String openidHash,
    String openvikingUserId,
    String senderId
) {}
