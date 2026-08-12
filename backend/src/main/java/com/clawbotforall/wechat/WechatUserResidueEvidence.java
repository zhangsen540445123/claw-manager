package com.clawbotforall.wechat;

import java.util.List;

/** Strong, cross-source evidence used to clean a historical user residue without a paired-account row. */
public record WechatUserResidueEvidence(
    String accountId,
    String wechatUserId,
    String agentId,
    String openvikingUserId,
    List<String> apiPeerIds,
    List<String> sessionIds,
    List<String> protectedAgentIds,
    List<String> evidenceTypes
) {
  public WechatUserResidueEvidence {
    apiPeerIds = apiPeerIds == null ? List.of() : List.copyOf(apiPeerIds);
    sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
    protectedAgentIds = protectedAgentIds == null ? List.of() : List.copyOf(protectedAgentIds);
    evidenceTypes = evidenceTypes == null ? List.of() : List.copyOf(evidenceTypes);
  }

  public WechatUserResidueEvidence(
      String accountId,
      String wechatUserId,
      String agentId,
      String openvikingUserId,
      List<String> apiPeerIds,
      List<String> sessionIds,
      List<String> evidenceTypes
  ) {
    this(accountId, wechatUserId, agentId, openvikingUserId,
        apiPeerIds, sessionIds, List.of(), evidenceTypes);
  }
}
