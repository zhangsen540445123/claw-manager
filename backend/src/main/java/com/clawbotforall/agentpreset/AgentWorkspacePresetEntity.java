package com.clawbotforall.agentpreset;

public class AgentWorkspacePresetEntity {
  private String id;
  private int version;
  private String agentsMd;
  private String soulMd;
  private String identityMd;
  private String toolsMd;
  private String heartbeatMd;
  private String userMd;
  private String updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public int getVersion() { return version; }
  public void setVersion(int version) { this.version = version; }
  public String getAgentsMd() { return agentsMd; }
  public void setAgentsMd(String agentsMd) { this.agentsMd = agentsMd; }
  public String getSoulMd() { return soulMd; }
  public void setSoulMd(String soulMd) { this.soulMd = soulMd; }
  public String getIdentityMd() { return identityMd; }
  public void setIdentityMd(String identityMd) { this.identityMd = identityMd; }
  public String getToolsMd() { return toolsMd; }
  public void setToolsMd(String toolsMd) { this.toolsMd = toolsMd; }
  public String getHeartbeatMd() { return heartbeatMd; }
  public void setHeartbeatMd(String heartbeatMd) { this.heartbeatMd = heartbeatMd; }
  public String getUserMd() { return userMd; }
  public void setUserMd(String userMd) { this.userMd = userMd; }
  public String getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
