package com.clawbotforall.wechat;

/** 不暴露用户原始身份快照的清理任务响应。 */
public record PublicWechatUserCleanupOperation(
    String operationId,
    String instanceId,
    String source,
    String status,
    String stage,
    int attemptCount,
    String error,
    int deletedBindings,
    int deletedFiles,
    int deletedDatabaseRows,
    boolean retryable,
    String createdAt,
    String updatedAt,
    String completedAt
) {
  public static PublicWechatUserCleanupOperation from(WechatUserCleanupOperationEntity operation) {
    return new PublicWechatUserCleanupOperation(
        operation.getOperationId(), operation.getInstanceId(), operation.getSource(), operation.getStatus(),
        operation.getStage(), operation.getAttemptCount(), operation.getLastError(), operation.getDeletedBindings(),
        operation.getDeletedFiles(), operation.getDeletedDatabaseRows(),
        "cleanup_failed".equals(operation.getStatus()), operation.getCreatedAt(), operation.getUpdatedAt(),
        operation.getCompletedAt());
  }
}
