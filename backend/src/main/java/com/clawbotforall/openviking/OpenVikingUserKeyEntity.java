package com.clawbotforall.openviking;

public class OpenVikingUserKeyEntity {

  private String accountId;
  private String openvikingUserId;
  private String userKey;
  private String createdAt;
  private String updatedAt;

  public String getAccountId() {
    return accountId;
  }

  public void setAccountId(String accountId) {
    this.accountId = accountId;
  }

  public String getOpenvikingUserId() {
    return openvikingUserId;
  }

  public void setOpenvikingUserId(String openvikingUserId) {
    this.openvikingUserId = openvikingUserId;
  }

  public String getUserKey() {
    return userKey;
  }

  public void setUserKey(String userKey) {
    this.userKey = userKey;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
