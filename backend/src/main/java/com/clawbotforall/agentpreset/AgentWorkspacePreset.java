package com.clawbotforall.agentpreset;

public record AgentWorkspacePreset(
    int version,
    String agentsMd,
    String soulMd,
    String identityMd,
    String toolsMd,
    String heartbeatMd,
    String userMd
) {
  public static AgentWorkspacePreset defaults() {
    return new AgentWorkspacePreset(
        0,
        "# AGENTS.md - Claw Manager Workspace\n\n"
            + "This workspace belongs to one OpenClaw user.\n"
            + "Use sender-scoped OpenViking for user memory and preferences.\n"
            + "Do not expose credentials, identity fields, or internal paths.\n",
        "# SOUL.md - Claw Manager Assistant\n\n"
            + "Be warm, practical, concise, and honest about tool failures.\n",
        "# IDENTITY.md - Claw Manager Assistant\n\n"
            + "Name: Claw Manager Assistant\n",
        "# TOOLS.md - Local Tool Notes\n\n"
            + "Use the registered Bridge and workspace tools for this Agent.\n",
        "# HEARTBEAT.md\n\n"
            + "No scheduled workspace checks are configured.\n",
        "# USER.md - Current User\n\n"
            + "User facts may be stored in this Agent workspace or sender-scoped OpenViking.\n"
    );
  }
}
