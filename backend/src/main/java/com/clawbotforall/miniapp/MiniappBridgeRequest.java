package com.clawbotforall.miniapp;

import java.util.Map;

public record MiniappBridgeRequest(
    String instanceId,
    String requesterSenderId,
    Map<String, Object> parameters,
    String requestId
) {}
