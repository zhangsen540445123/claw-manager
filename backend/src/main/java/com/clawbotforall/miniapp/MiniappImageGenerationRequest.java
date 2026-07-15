package com.clawbotforall.miniapp;

public record MiniappImageGenerationRequest(String instanceId, String requesterSenderId, String requestId,
    String prompt, String size, String quality) {}
