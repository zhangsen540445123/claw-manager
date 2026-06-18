package com.clawbotforall.instance;

/**
 * 实例创建进度和 Gateway 就绪进度的持久化实体。
 */
public class InstanceProvisioningEntity {

  private String instanceId;
  private String status;
  private int percent;
  private String stage;
  private String message;
  private String gatewayStartedAt;
  private String updatedAt;

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getPercent() {
    return percent;
  }

  public void setPercent(int percent) {
    this.percent = percent;
  }

  public String getStage() {
    return stage;
  }

  public void setStage(String stage) {
    this.stage = stage;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getGatewayStartedAt() {
    return gatewayStartedAt;
  }

  public void setGatewayStartedAt(String gatewayStartedAt) {
    this.gatewayStartedAt = gatewayStartedAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
