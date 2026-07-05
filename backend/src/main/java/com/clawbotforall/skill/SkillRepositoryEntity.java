package com.clawbotforall.skill;

public class SkillRepositoryEntity {
  private String id;
  private String name;
  private String repoUrl;
  private String branch;
  private String authType;
  private String accessToken;
  private String tokenPreview;
  private String lastCommitSha;
  private String lastPullStatus;
  private String lastPullMessage;
  private String lastPulledAt;
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

  public String getRepoUrl() {
    return repoUrl;
  }

  public void setRepoUrl(String repoUrl) {
    this.repoUrl = repoUrl;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public String getAuthType() {
    return authType;
  }

  public void setAuthType(String authType) {
    this.authType = authType;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public String getTokenPreview() {
    return tokenPreview;
  }

  public void setTokenPreview(String tokenPreview) {
    this.tokenPreview = tokenPreview;
  }

  public String getLastCommitSha() {
    return lastCommitSha;
  }

  public void setLastCommitSha(String lastCommitSha) {
    this.lastCommitSha = lastCommitSha;
  }

  public String getLastPullStatus() {
    return lastPullStatus;
  }

  public void setLastPullStatus(String lastPullStatus) {
    this.lastPullStatus = lastPullStatus;
  }

  public String getLastPullMessage() {
    return lastPullMessage;
  }

  public void setLastPullMessage(String lastPullMessage) {
    this.lastPullMessage = lastPullMessage;
  }

  public String getLastPulledAt() {
    return lastPulledAt;
  }

  public void setLastPulledAt(String lastPulledAt) {
    this.lastPulledAt = lastPulledAt;
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
