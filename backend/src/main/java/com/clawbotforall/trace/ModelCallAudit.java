package com.clawbotforall.trace;

public record ModelCallAudit(
    String id,
    String eventType,
    String instanceId,
    String agentId,
    String sessionId,
    String sessionKeyHash,
    String runId,
    String callId,
    String provider,
    String model,
    String apiTransport,
    String pluginVersion,
    String systemPrompt,
    String prompt,
    String historyMessagesJson,
    Integer imagesCount,
    String outputText,
    String usageJson,
    String stopReason,
    Long durationMs,
    String outcome,
    String errorCategory,
    String createdAt,
    String expiresAt
) {}
