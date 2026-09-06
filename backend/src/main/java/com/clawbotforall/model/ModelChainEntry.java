package com.clawbotforall.model;

/**
 * 模型链上的一环：来源预设及其运行时规范化模型。
 */
public record ModelChainEntry(
    NormalizedModelSelection model,
    String presetId
) {}
