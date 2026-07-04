package com.clawbotforall.miniapp;

public record MiniappHmacHeaders(
    String appId,
    String timestamp,
    String nonce,
    String signature
) {}
