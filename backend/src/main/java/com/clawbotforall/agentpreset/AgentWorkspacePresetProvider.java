package com.clawbotforall.agentpreset;

/** Provides the current workspace seed without coupling instance file generation to persistence. */
public interface AgentWorkspacePresetProvider {

  AgentWorkspacePreset current();
}
