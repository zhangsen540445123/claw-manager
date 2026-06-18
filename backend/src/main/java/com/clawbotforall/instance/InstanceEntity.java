package com.clawbotforall.instance;

/**
 * OpenClaw 实例的持久化实体。
 */
public class InstanceEntity {

  private String id;
  private String name;
  private String slug;
  private String status;
  private int port;
  private String dashboardUrl;
  private String containerName;
  private String gatewayToken;
  private String pluginsAllow;
  private String pluginsEntries;
  private String createdAt;
  private String updatedAt;

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

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getDashboardUrl() {
    return dashboardUrl;
  }

  public void setDashboardUrl(String dashboardUrl) {
    this.dashboardUrl = dashboardUrl;
  }

  public String getContainerName() {
    return containerName;
  }

  public void setContainerName(String containerName) {
    this.containerName = containerName;
  }

  public String getGatewayToken() {
    return gatewayToken;
  }

  public void setGatewayToken(String gatewayToken) {
    this.gatewayToken = gatewayToken;
  }

  public String getPluginsAllow() {
    return pluginsAllow;
  }

  public void setPluginsAllow(String pluginsAllow) {
    this.pluginsAllow = pluginsAllow;
  }

  public String getPluginsEntries() {
    return pluginsEntries;
  }

  public void setPluginsEntries(String pluginsEntries) {
    this.pluginsEntries = pluginsEntries;
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
