package com.clawbotforall.externalapi;

public class ExternalApiUserRouteEntity {
  private String openid;
  private String openidHash;
  private String openvikingUserId;
  private String instanceId;
  private String createdAt;
  private String updatedAt;
  private String lastUsedAt;

  public String getOpenid() {
    return openid;
  }

  public void setOpenid(String openid) {
    this.openid = openid;
  }

  public String getOpenidHash() {
    return openidHash;
  }

  public void setOpenidHash(String openidHash) {
    this.openidHash = openidHash;
  }

  public String getOpenvikingUserId() {
    return openvikingUserId;
  }

  public void setOpenvikingUserId(String openvikingUserId) {
    this.openvikingUserId = openvikingUserId;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
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

  public String getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(String lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }
}
