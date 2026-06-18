package com.clawbotforall.instance;

/**
 * 微信绑定和运行通道状态的持久化实体。
 */
public class InstanceWechatBindingEntity {

  private String instanceId;
  private String status;
  private String qrMode;
  private String qrPayload;
  private String qrLink;
  private String outputSnippet;
  private boolean runtimeReady;
  private String runtimeStatus;
  private String runtimeMessage;
  private String runtimeUpdatedAt;
  private String updatedAt;
  private String qrExpiresAt;

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

  public String getQrMode() {
    return qrMode;
  }

  public void setQrMode(String qrMode) {
    this.qrMode = qrMode;
  }

  public String getQrPayload() {
    return qrPayload;
  }

  public void setQrPayload(String qrPayload) {
    this.qrPayload = qrPayload;
  }

  public String getQrLink() {
    return qrLink;
  }

  public void setQrLink(String qrLink) {
    this.qrLink = qrLink;
  }

  public String getOutputSnippet() {
    return outputSnippet;
  }

  public void setOutputSnippet(String outputSnippet) {
    this.outputSnippet = outputSnippet;
  }

  public boolean isRuntimeReady() {
    return runtimeReady;
  }

  public void setRuntimeReady(boolean runtimeReady) {
    this.runtimeReady = runtimeReady;
  }

  public String getRuntimeStatus() {
    return runtimeStatus;
  }

  public void setRuntimeStatus(String runtimeStatus) {
    this.runtimeStatus = runtimeStatus;
  }

  public String getRuntimeMessage() {
    return runtimeMessage;
  }

  public void setRuntimeMessage(String runtimeMessage) {
    this.runtimeMessage = runtimeMessage;
  }

  public String getRuntimeUpdatedAt() {
    return runtimeUpdatedAt;
  }

  public void setRuntimeUpdatedAt(String runtimeUpdatedAt) {
    this.runtimeUpdatedAt = runtimeUpdatedAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getQrExpiresAt() {
    return qrExpiresAt;
  }

  public void setQrExpiresAt(String qrExpiresAt) {
    this.qrExpiresAt = qrExpiresAt;
  }
}
