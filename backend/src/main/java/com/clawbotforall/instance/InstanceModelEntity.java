package com.clawbotforall.instance;

/**
 * 实例模型链中单个模型配置的持久化实体。
 */
public class InstanceModelEntity {

  private String instanceId;
  private int sortOrder;
  private String presetId;
  private String providerKey;
  private String providerId;
  private String modelId;
  private String apiMode;
  private String authType;
  private String authProviderId;
  private String authMethodId;
  private String baseUrl;
  private String apiKey;
  private String providerConfig;
  private String extra;

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public String getPresetId() {
    return presetId;
  }

  public void setPresetId(String presetId) {
    this.presetId = presetId;
  }

  public String getProviderKey() {
    return providerKey;
  }

  public void setProviderKey(String providerKey) {
    this.providerKey = providerKey;
  }

  public String getProviderId() {
    return providerId;
  }

  public void setProviderId(String providerId) {
    this.providerId = providerId;
  }

  public String getModelId() {
    return modelId;
  }

  public void setModelId(String modelId) {
    this.modelId = modelId;
  }

  public String getApiMode() {
    return apiMode;
  }

  public void setApiMode(String apiMode) {
    this.apiMode = apiMode;
  }

  public String getAuthType() {
    return authType;
  }

  public void setAuthType(String authType) {
    this.authType = authType;
  }

  public String getAuthProviderId() {
    return authProviderId;
  }

  public void setAuthProviderId(String authProviderId) {
    this.authProviderId = authProviderId;
  }

  public String getAuthMethodId() {
    return authMethodId;
  }

  public void setAuthMethodId(String authMethodId) {
    this.authMethodId = authMethodId;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getProviderConfig() {
    return providerConfig;
  }

  public void setProviderConfig(String providerConfig) {
    this.providerConfig = providerConfig;
  }

  public String getExtra() {
    return extra;
  }

  public void setExtra(String extra) {
    this.extra = extra;
  }
}
