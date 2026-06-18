package com.clawbotforall.instance;

/**
 * 实例模型变更结果，以及是否需要重启。
 */
public record InstanceModelUpdateResult(
    InstanceEntity instance,
    boolean restartRequired
) {}
