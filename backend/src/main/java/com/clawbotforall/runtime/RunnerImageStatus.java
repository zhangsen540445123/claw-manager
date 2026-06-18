package com.clawbotforall.runtime;

/**
 * 本地 OpenClaw 运行镜像状态。
 */
public record RunnerImageStatus(
    String image,
    String status,
    String message,
    boolean present,
    String imageId,
    String updatedAt
) {}
