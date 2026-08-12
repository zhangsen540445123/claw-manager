package com.clawbotforall.instance;

/** 不暴露实例删除期间的原始用户身份快照，只返回进度和脱敏错误。 */
public record PublicInstanceDeleteOperation(
    String operationId,
    String instanceId,
    String instanceName,
    String containerName,
    boolean force,
    String status,
    String stage,
    int wechatAccountCount,
    int miniappBindingCount,
    String error,
    boolean retryable,
    String createdAt,
    String updatedAt,
    String completedAt
) {
  public static PublicInstanceDeleteOperation from(InstanceDeleteOperationEntity operation) {
    return new PublicInstanceDeleteOperation(
        operation.getOperationId(), operation.getInstanceId(), operation.getInstanceName(),
        operation.getContainerName(), operation.isForce(), operation.getStatus(), operation.getStage(),
        operation.getWechatAccountCount(), operation.getMiniappBindingCount(), operation.getLastError(),
        "delete_failed".equals(operation.getStatus()), operation.getCreatedAt(), operation.getUpdatedAt(),
        operation.getCompletedAt());
  }
}
