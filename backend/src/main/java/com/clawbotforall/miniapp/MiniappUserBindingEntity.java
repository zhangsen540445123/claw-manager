package com.clawbotforall.miniapp;

public class MiniappUserBindingEntity {
  private String openidHash;
  private String openid;
  private String instanceId;
  private String wechatUserId;
  private String openvikingUserId;
  private String bindStatus;
  private String currentBindToken;
  private String boundAt;
  private String createdAt;
  private String updatedAt;

  public String getOpenidHash() {
    return openidHash;
  }

  public void setOpenidHash(String openidHash) {
    this.openidHash = openidHash;
  }

  public String getOpenid() {
    return openid;
  }

  public void setOpenid(String openid) {
    this.openid = openid;
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

  public String getOpenvikingUserId() {
    return openvikingUserId;
  }

  public void setOpenvikingUserId(String openvikingUserId) {
    this.openvikingUserId = openvikingUserId;
  }

  public String getBindStatus() {
    return bindStatus;
  }

  public void setBindStatus(String bindStatus) {
    this.bindStatus = bindStatus;
  }

  public String getCurrentBindToken() {
    return currentBindToken;
  }

  public void setCurrentBindToken(String currentBindToken) {
    this.currentBindToken = currentBindToken;
  }

  public String getBoundAt() {
    return boundAt;
  }

  public void setBoundAt(String boundAt) {
    this.boundAt = boundAt;
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
