package com.clawbotforall.useragent;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public class UserAgentResolveRequest {
  private String instanceId;
  private String wechatUserId;

  public UserAgentResolveRequest() {
  }

  public UserAgentResolveRequest(String instanceId, String wechatUserId) {
    this.instanceId = instanceId;
    this.wechatUserId = wechatUserId;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public String getWechatUserId() {
    return wechatUserId;
  }

  public void setWechatUserId(String wechatUserId) {
    this.wechatUserId = wechatUserId;
  }

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("不支持的请求字段：" + field);
  }
}
