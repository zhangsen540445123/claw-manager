package com.clawbotforall.agentpreset;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentWorkspacePresetMapper {
  AgentWorkspacePresetEntity findGlobal();
  int upsert(AgentWorkspacePresetEntity preset);
}
