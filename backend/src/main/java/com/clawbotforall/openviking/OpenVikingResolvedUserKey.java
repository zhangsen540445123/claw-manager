package com.clawbotforall.openviking;

public record OpenVikingResolvedUserKey(
    String accountId,
    String openvikingUserId,
    String userKey,
    boolean created
) {}
