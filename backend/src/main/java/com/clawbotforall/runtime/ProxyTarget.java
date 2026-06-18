package com.clawbotforall.runtime;

/**
 * 代理 Control UI 请求时解析出的网络目标。
 */
public record ProxyTarget(
    String host,
    int port,
    String mode,
    String network
) {}
