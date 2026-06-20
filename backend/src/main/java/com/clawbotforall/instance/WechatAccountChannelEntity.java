package com.clawbotforall.instance;

/**
 * 已绑定微信账号在实例内的通道状态。
 */
public class WechatAccountChannelEntity {

  private String accountId;
  private String instanceId;
  private String wechatUserId;
  private String status;
  private String message;
  private String outputSnippet;
  private String lastStartedAt;
  private String lastErrorAt;
  private String updatedAt;

  public String getAccountId() {
    return accountId;
  }

  public void setAccountId(String accountId) {
    this.accountId = accountId;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public String getWechatUserId() {
    return wechatUserId;
  }

  public void setWechatUserId(String wechatUserId) {
    this.wechatUserId = wechatUserId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getOutputSnippet() {
    return outputSnippet;
  }

  public void setOutputSnippet(String outputSnippet) {
    this.outputSnippet = outputSnippet;
  }

  public String getLastStartedAt() {
    return lastStartedAt;
  }

  public void setLastStartedAt(String lastStartedAt) {
    this.lastStartedAt = lastStartedAt;
  }

  public String getLastErrorAt() {
    return lastErrorAt;
  }

  public void setLastErrorAt(String lastErrorAt) {
    this.lastErrorAt = lastErrorAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
