package com.clawbotforall.openviking;

public record OpenVikingSenderIdentity(
    String senderId,
    String senderHash,
    String openVikingUserId
) {}
