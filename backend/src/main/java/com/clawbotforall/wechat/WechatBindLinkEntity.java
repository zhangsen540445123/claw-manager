package com.clawbotforall.wechat;

/**
 * 管理员生成的微信扫码绑定链接实体。
 */
public class WechatBindLinkEntity {

  private String token;
  private String mode;
  private String phone;
  private String instanceId;
  private String targetAccountId;
  private String scannedWechatUserId;
  private String status;
  private String qrMode;
  private String qrPayload;
  private String qrLink;
  private String qrExpiresAt;
  private String errorMessage;
  private String createdByAdminId;
  private String createdAt;
  private String startedAt;
  private String expiresAt;
  private String completedAt;
  private String updatedAt;

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public String getTargetAccountId() {
    return targetAccountId;
  }

  public void setTargetAccountId(String targetAccountId) {
    this.targetAccountId = targetAccountId;
  }

  public String getScannedWechatUserId() {
    return scannedWechatUserId;
  }

  public void setScannedWechatUserId(String scannedWechatUserId) {
    this.scannedWechatUserId = scannedWechatUserId;
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

  public String getQrExpiresAt() {
    return qrExpiresAt;
  }

  public void setQrExpiresAt(String qrExpiresAt) {
    this.qrExpiresAt = qrExpiresAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public String getCreatedByAdminId() {
    return createdByAdminId;
  }

  public void setCreatedByAdminId(String createdByAdminId) {
    this.createdByAdminId = createdByAdminId;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(String startedAt) {
    this.startedAt = startedAt;
  }

  public String getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(String expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(String completedAt) {
    this.completedAt = completedAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
