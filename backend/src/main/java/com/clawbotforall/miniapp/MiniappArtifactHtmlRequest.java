package com.clawbotforall.miniapp;

public record MiniappArtifactHtmlRequest(
    String instanceId,
    String requesterSenderId,
    String requestId,
    String cmTraceId,
    String title,
    String contentKey,
    String htmlContent
) {}
