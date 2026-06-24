package com.clawbotforall.openviking;

/**
 * 全局 OpenViking 预置配置。
 */
public class OpenVikingSettingsEntity {

  private String id;
  private String baseUrl;
  private boolean trustedModeEnabled;
  private String accountId;
  private String pluginPackage;
  private String rootApiKey;
  private String createdAt;
  private String updatedAt;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public boolean isTrustedModeEnabled() {
    return trustedModeEnabled;
  }

  public boolean getTrustedModeEnabled() {
    return trustedModeEnabled;
  }

  public void setTrustedModeEnabled(boolean trustedModeEnabled) {
    this.trustedModeEnabled = trustedModeEnabled;
  }

  public String getAccountId() {
    return accountId;
  }

  public void setAccountId(String accountId) {
    this.accountId = accountId;
  }

  public String getPluginPackage() {
    return pluginPackage;
  }

  public void setPluginPackage(String pluginPackage) {
    this.pluginPackage = pluginPackage;
  }

  public String getRootApiKey() {
    return rootApiKey;
  }

  public void setRootApiKey(String rootApiKey) {
    this.rootApiKey = rootApiKey;
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
