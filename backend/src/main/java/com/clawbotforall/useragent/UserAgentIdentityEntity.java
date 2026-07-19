package com.clawbotforall.useragent;

public class UserAgentIdentityEntity {
  private String agentId;
  private String wechatUserId;
  private String openvikingUserId;
  private String createdAt;
  private String updatedAt;

  public String getAgentId() {
    return agentId;
  }

  public void setAgentId(String agentId) {
    this.agentId = agentId;
  }

  public String getWechatUserId() {
    return wechatUserId;
  }

  public void setWechatUserId(String wechatUserId) {
    this.wechatUserId = wechatUserId;
  }

  public String getOpenvikingUserId() {
    return openvikingUserId;
  }

  public void setOpenvikingUserId(String openvikingUserId) {
    this.openvikingUserId = openvikingUserId;
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
