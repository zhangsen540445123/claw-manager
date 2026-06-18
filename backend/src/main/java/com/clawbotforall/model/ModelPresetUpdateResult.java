package com.clawbotforall.model;

/**
 * 模型预设更新后的预设信息与同步结果。
 */
public record ModelPresetUpdateResult(
    PublicModelPreset preset,
    ModelPresetSyncResult sync
) {}
