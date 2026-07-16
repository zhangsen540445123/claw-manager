package com.clawbotforall.trace;

import java.util.Map;

public record IntegrationTraceEventRequest(String traceId, String parentRequestId, String component,
    String stage, String status, String channel, String instanceId, String senderHash, String sessionKeyHash,
    String toolName, String requestId, Integer httpStatus, Integer businessCode, Long elapsedMs,
    String errorCode, String errorMessage, Map<String, Object> details) {}
