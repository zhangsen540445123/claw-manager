package com.clawbotforall.instance;

/**
 * 已绑定微信账号、手机号和 OpenClaw 实例的一对一映射实体。
 */
public class WechatPairedAccountEntity {

  private String accountId;
  private String phone;
  private String instanceId;
  private String wechatUserId;
  private String remark;
  private String baseUrl;
  private String savedAt;
  private String boundAt;
  private String updatedAt;

  public String getAccountId() {
    return accountId;
  }

  public void setAccountId(String accountId) {
    this.accountId = accountId;
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

  public String getWechatUserId() {
    return wechatUserId;
  }

  public void setWechatUserId(String wechatUserId) {
    this.wechatUserId = wechatUserId;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getSavedAt() {
    return savedAt;
  }

  public void setSavedAt(String savedAt) {
    this.savedAt = savedAt;
  }

  public String getBoundAt() {
    return boundAt;
  }

  public void setBoundAt(String boundAt) {
    this.boundAt = boundAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
