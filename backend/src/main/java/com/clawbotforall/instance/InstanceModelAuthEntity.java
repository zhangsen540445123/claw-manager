package com.clawbotforall.instance;

/**
 * 模型认证流程状态的持久化实体。
 */
public class InstanceModelAuthEntity {

  private String instanceId;
  private String status;
  private String message;
  private String outputSnippet;
  private String authUrl;
  private String promptLabel;
  private boolean needsInput;
  private String updatedAt;

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
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

  public String getOutputSnippet() {
    return outputSnippet;
  }

  public void setOutputSnippet(String outputSnippet) {
    this.outputSnippet = outputSnippet;
  }

  public String getAuthUrl() {
    return authUrl;
  }

  public void setAuthUrl(String authUrl) {
    this.authUrl = authUrl;
  }

  public String getPromptLabel() {
    return promptLabel;
  }

  public void setPromptLabel(String promptLabel) {
    this.promptLabel = promptLabel;
  }

  public boolean isNeedsInput() {
    return needsInput;
  }

  public void setNeedsInput(boolean needsInput) {
    this.needsInput = needsInput;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
