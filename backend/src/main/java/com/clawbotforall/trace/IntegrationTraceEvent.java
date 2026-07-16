package com.clawbotforall.trace;

public record IntegrationTraceEvent(String id, String traceId, String parentRequestId, String component,
    String stage, String status, String channel, String instanceId, String senderHash, String sessionKeyHash,
    String toolName, String requestId, Integer httpStatus, Integer businessCode, Long elapsedMs,
    String errorCode, String errorMessage, String detailJson, String createdAt) {}
