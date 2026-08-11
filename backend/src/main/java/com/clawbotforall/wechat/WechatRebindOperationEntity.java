package com.clawbotforall.wechat;

public class WechatRebindOperationEntity {
  private String bindToken;
  private String phone;
  private String wechatUserId;
  private String oldInstanceId;
  private String oldAccountId;
  private String newInstanceId;
  private String newAccountId;
  private String oldAgentId;
  private String newAgentId;
  private String openvikingUserId;
  private String apiPeerIdsJson;
  private String oldSessionIdsJson;
  private String accountSnapshotJson;
  private String status;
  private String stage;
  private int attemptCount;
  private String lastError;
  private String createdAt;
  private String updatedAt;
  private String completedAt;

  public String getBindToken() { return bindToken; }
  public void setBindToken(String value) { bindToken = value; }
  public String getPhone() { return phone; }
  public void setPhone(String value) { phone = value; }
  public String getWechatUserId() { return wechatUserId; }
  public void setWechatUserId(String value) { wechatUserId = value; }
  public String getOldInstanceId() { return oldInstanceId; }
  public void setOldInstanceId(String value) { oldInstanceId = value; }
  public String getOldAccountId() { return oldAccountId; }
  public void setOldAccountId(String value) { oldAccountId = value; }
  public String getNewInstanceId() { return newInstanceId; }
  public void setNewInstanceId(String value) { newInstanceId = value; }
  public String getNewAccountId() { return newAccountId; }
  public void setNewAccountId(String value) { newAccountId = value; }
  public String getOldAgentId() { return oldAgentId; }
  public void setOldAgentId(String value) { oldAgentId = value; }
  public String getNewAgentId() { return newAgentId; }
  public void setNewAgentId(String value) { newAgentId = value; }
  public String getOpenvikingUserId() { return openvikingUserId; }
  public void setOpenvikingUserId(String value) { openvikingUserId = value; }
  public String getApiPeerIdsJson() { return apiPeerIdsJson; }
  public void setApiPeerIdsJson(String value) { apiPeerIdsJson = value; }
  public String getOldSessionIdsJson() { return oldSessionIdsJson; }
  public void setOldSessionIdsJson(String value) { oldSessionIdsJson = value; }
  public String getAccountSnapshotJson() { return accountSnapshotJson; }
  public void setAccountSnapshotJson(String value) { accountSnapshotJson = value; }
  public String getStatus() { return status; }
  public void setStatus(String value) { status = value; }
  public String getStage() { return stage; }
  public void setStage(String value) { stage = value; }
  public int getAttemptCount() { return attemptCount; }
  public void setAttemptCount(int value) { attemptCount = value; }
  public String getLastError() { return lastError; }
  public void setLastError(String value) { lastError = value; }
  public String getCreatedAt() { return createdAt; }
  public void setCreatedAt(String value) { createdAt = value; }
  public String getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(String value) { updatedAt = value; }
  public String getCompletedAt() { return completedAt; }
  public void setCompletedAt(String value) { completedAt = value; }
}
