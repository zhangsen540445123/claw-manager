package com.clawbotforall.model;

/**
 * 可复用模型预设配置的持久化实体。
 */
public class ModelPresetEntity {

  private String id;
  private String name;
  private boolean isDefault;
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
  private String fallbackPresetIds;
  private int contextWindow;
  private int maxTokens;
  private String createdAt;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public boolean isDefault() {
    return isDefault;
  }

  public boolean getIsDefault() {
    return isDefault;
  }

  public void setDefault(boolean aDefault) {
    isDefault = aDefault;
  }

  public void setIsDefault(boolean aDefault) {
    isDefault = aDefault;
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

  public String getFallbackPresetIds() {
    return fallbackPresetIds;
  }

  public void setFallbackPresetIds(String fallbackPresetIds) {
    this.fallbackPresetIds = fallbackPresetIds;
  }

  public int getContextWindow() {
    return contextWindow;
  }

  public void setContextWindow(int contextWindow) {
    this.contextWindow = contextWindow;
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }
}
