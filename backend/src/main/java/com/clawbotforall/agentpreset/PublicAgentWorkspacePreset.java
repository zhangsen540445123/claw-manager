package com.clawbotforall.agentpreset;

public record PublicAgentWorkspacePreset(
    int version,
    String agentsMd,
    String soulMd,
    String identityMd,
    String toolsMd,
    String heartbeatMd,
    String userMd,
    String updatedAt
) {
  static PublicAgentWorkspacePreset from(AgentWorkspacePreset preset, String updatedAt) {
    return new PublicAgentWorkspacePreset(
        preset.version(), preset.agentsMd(), preset.soulMd(), preset.identityMd(),
        preset.toolsMd(), preset.heartbeatMd(), preset.userMd(), updatedAt == null ? "" : updatedAt
    );
  }
}
