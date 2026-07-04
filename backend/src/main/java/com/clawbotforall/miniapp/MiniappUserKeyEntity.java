package com.clawbotforall.miniapp;

public class MiniappUserKeyEntity {
  private String openidHash;
  private String openid;
  private String userKey;
  private String keyPreview;
  private boolean enabled;
  private String createdAt;
  private String updatedAt;
  private String lastUsedAt;

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

  public String getUserKey() {
    return userKey;
  }

  public void setUserKey(String userKey) {
    this.userKey = userKey;
  }

  public String getKeyPreview() {
    return keyPreview;
  }

  public void setKeyPreview(String keyPreview) {
    this.keyPreview = keyPreview;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
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
