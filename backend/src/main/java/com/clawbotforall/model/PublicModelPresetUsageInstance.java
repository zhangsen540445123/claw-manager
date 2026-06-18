package com.clawbotforall.model;

import java.util.List;

/**
 * 引用某个模型预设的实例摘要。
 */
public record PublicModelPresetUsageInstance(
    String id,
    String name,
    String status,
    List<Integer> modelIndexes
) {}
