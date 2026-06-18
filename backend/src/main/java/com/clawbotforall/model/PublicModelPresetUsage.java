package com.clawbotforall.model;

import java.util.List;

/**
 * 模型预设被实例引用的公开查询结果。
 */
public record PublicModelPresetUsage(
    List<PublicModelPresetUsageInstance> instances
) {}
