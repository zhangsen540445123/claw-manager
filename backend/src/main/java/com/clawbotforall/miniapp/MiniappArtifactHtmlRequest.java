package com.clawbotforall.miniapp;

public record MiniappArtifactHtmlRequest(
    String instanceId,
    String requesterSenderId,
    String requestId,
    String title,
    String contentKey,
    String htmlContent
) {}
