package com.clawbotforall.instance;

public class InstanceDeleteOperationEntity {
  private String operationId;
  private String instanceId;
  private String instanceName;
  private String containerName;
  private boolean force;
  private String status;
  private String stage;
  private int wechatAccountCount;
  private int miniappBindingCount;
  private String cleanupOperationIdsJson;
  private String lastError;
  private String createdAt;
  private String updatedAt;
  private String completedAt;

  public String getOperationId() { return operationId; }
  public void setOperationId(String value) { operationId = value; }
  public String getInstanceId() { return instanceId; }
  public void setInstanceId(String value) { instanceId = value; }
  public String getInstanceName() { return instanceName; }
  public void setInstanceName(String value) { instanceName = value; }
  public String getContainerName() { return containerName; }
  public void setContainerName(String value) { containerName = value; }
  public boolean isForce() { return force; }
  public void setForce(boolean value) { force = value; }
  public String getStatus() { return status; }
  public void setStatus(String value) { status = value; }
  public String getStage() { return stage; }
  public void setStage(String value) { stage = value; }
  public int getWechatAccountCount() { return wechatAccountCount; }
  public void setWechatAccountCount(int value) { wechatAccountCount = value; }
  public int getMiniappBindingCount() { return miniappBindingCount; }
  public void setMiniappBindingCount(int value) { miniappBindingCount = value; }
  public String getCleanupOperationIdsJson() { return cleanupOperationIdsJson; }
  public void setCleanupOperationIdsJson(String value) { cleanupOperationIdsJson = value; }
  public String getLastError() { return lastError; }
  public void setLastError(String value) { lastError = value; }
  public String getCreatedAt() { return createdAt; }
  public void setCreatedAt(String value) { createdAt = value; }
  public String getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(String value) { updatedAt = value; }
  public String getCompletedAt() { return completedAt; }
  public void setCompletedAt(String value) { completedAt = value; }
}
