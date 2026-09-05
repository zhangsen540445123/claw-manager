package com.clawbotforall.agentpreset;

import java.util.List;

/**
 * 后台手动将当前 Agent 工作区预设推送到全部已物化 Agent 工作区的结果摘要。
 */
public record AgentWorkspacePresetPushResult(
    int version,
    int instancesProcessed,
    int agentsUpdated,
    int filesWritten,
    List<Failure> failures
) {
  public record Failure(String instanceId, String agentId, String message) {}
}
