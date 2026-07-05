package com.clawbotforall.skill;

public class SkillInstanceSyncEntity {
  private String instanceId;
  private String skillName;
  private String skillId;
  private String repositoryId;
  private String sourceCommitSha;
  private String status;
  private String message;
  private String syncedAt;
  private String updatedAt;

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public String getSkillName() {
    return skillName;
  }

  public void setSkillName(String skillName) {
    this.skillName = skillName;
  }

  public String getSkillId() {
    return skillId;
  }

  public void setSkillId(String skillId) {
    this.skillId = skillId;
  }

  public String getRepositoryId() {
    return repositoryId;
  }

  public void setRepositoryId(String repositoryId) {
    this.repositoryId = repositoryId;
  }

  public String getSourceCommitSha() {
    return sourceCommitSha;
  }

  public void setSourceCommitSha(String sourceCommitSha) {
    this.sourceCommitSha = sourceCommitSha;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getSyncedAt() {
    return syncedAt;
  }

  public void setSyncedAt(String syncedAt) {
    this.syncedAt = syncedAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
