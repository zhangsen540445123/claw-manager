package com.clawbotforall.trace;

import java.time.Instant;
import java.util.List;

public record ModelCallAuditEventRequest(
    String eventType,
    String instanceId,
    String agentId,
    String sessionId,
    String sessionKey,
    String runId,
    String callId,
    String provider,
    String model,
    String api,
    String transport,
    String apiTransport,
    String pluginVersion,
    String systemPrompt,
    String prompt,
    Object historyMessages,
    Integer imagesCount,
    Object output,
    Object usage,
    String stopReason,
    Long durationMs,
    String outcome,
    String errorCategory,
    String createdAt
) {
  public ModelCallAuditEventRequest(
      String eventType, String instanceId, String agentId, String sessionId, String sessionKey,
      String runId, String callId, String provider, String model, String systemPrompt, String prompt,
      List<?> historyMessages, Integer imagesCount, Object output, Object usage, String stopReason,
      Long durationMs, String outcome, String errorCategory
  ) {
    this(eventType, instanceId, agentId, sessionId, sessionKey, runId, callId, provider, model,
        null, null, null, null, systemPrompt, prompt, historyMessages, imagesCount, output, usage, stopReason, durationMs,
        outcome, errorCategory, (String) null);
  }

  public ModelCallAuditEventRequest(
      String eventType, String instanceId, String agentId, String sessionId, String sessionKey,
      String runId, String callId, String provider, String model, String systemPrompt, String prompt,
      List<?> historyMessages, Integer imagesCount, Object output, Object usage, String stopReason,
      Long durationMs, String outcome, String errorCategory, Instant createdAt
  ) {
    this(eventType, instanceId, agentId, sessionId, sessionKey, runId, callId, provider, model,
        null, null, null, null, systemPrompt, prompt, historyMessages, imagesCount, output, usage, stopReason, durationMs,
        outcome, errorCategory, createdAt == null ? null : createdAt.toString());
  }
}
