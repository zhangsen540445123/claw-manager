package com.clawbotforall.skill;

public class SkillDefinitionEntity {
  private String id;
  private String repositoryId;
  private String repositoryName;
  private String skillName;
  private String originalName;
  private String relativePath;
  private String description;
  private String contentHash;
  private String warnings;
  private boolean syncable;
  private String lastCommitSha;
  private String createdAt;
  private String updatedAt;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getRepositoryId() {
    return repositoryId;
  }

  public void setRepositoryId(String repositoryId) {
    this.repositoryId = repositoryId;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getSkillName() {
    return skillName;
  }

  public void setSkillName(String skillName) {
    this.skillName = skillName;
  }

  public String getOriginalName() {
    return originalName;
  }

  public void setOriginalName(String originalName) {
    this.originalName = originalName;
  }

  public String getRelativePath() {
    return relativePath;
  }

  public void setRelativePath(String relativePath) {
    this.relativePath = relativePath;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public String getWarnings() {
    return warnings;
  }

  public void setWarnings(String warnings) {
    this.warnings = warnings;
  }

  public boolean isSyncable() {
    return syncable;
  }

  public void setSyncable(boolean syncable) {
    this.syncable = syncable;
  }

  public String getLastCommitSha() {
    return lastCommitSha;
  }

  public void setLastCommitSha(String lastCommitSha) {
    this.lastCommitSha = lastCommitSha;
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
