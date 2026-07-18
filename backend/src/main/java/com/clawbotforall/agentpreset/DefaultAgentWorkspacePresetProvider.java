package com.clawbotforall.agentpreset;

import org.springframework.stereotype.Component;

/** Fallback used by tests and before the persisted preset has been initialized. */
@Component("defaultAgentWorkspacePresetProvider")
public class DefaultAgentWorkspacePresetProvider implements AgentWorkspacePresetProvider {
  @Override
  public AgentWorkspacePreset current() {
    return AgentWorkspacePreset.defaults();
  }
}
