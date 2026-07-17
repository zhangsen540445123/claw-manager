package com.clawbotforall.miniapp;

public record MiniappGeneratedArtifactRequest(
    String instanceId,
    String requesterSenderId,
    String requestId,
    String cmTraceId,
    String generatedImageId,
    String title,
    String description
) {}
