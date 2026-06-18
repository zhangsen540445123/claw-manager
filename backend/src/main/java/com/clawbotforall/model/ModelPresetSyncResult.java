package com.clawbotforall.model;

import java.util.List;

/**
 * 模型预设保存后同步引用实例的结果。
 */
public record ModelPresetSyncResult(
    boolean requested,
    int affectedInstances,
    List<String> updatedInstanceIds,
    List<String> restartedInstanceIds
) {

  public static ModelPresetSyncResult notRequested(int affectedInstances) {
    return new ModelPresetSyncResult(false, affectedInstances, List.of(), List.of());
  }
}
