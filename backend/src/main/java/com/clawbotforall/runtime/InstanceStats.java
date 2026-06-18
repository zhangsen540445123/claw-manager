package com.clawbotforall.runtime;

/**
 * OpenClaw 容器的运行时资源使用快照。
 */
public record InstanceStats(
    String cpuPercent,
    String memUsage,
    String memPercent,
    String netIO,
    String pids
) {}
