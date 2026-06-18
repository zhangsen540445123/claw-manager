package com.clawbotforall.instance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * OpenClaw 实例及其相关状态的 API 安全响应模型。
 */
public record PublicInstance(
    String id,
    String name,
    String slug,
    String status,
    int port,
    String dashboardUrl,
    String containerName,
    String gatewayToken,
    String createdAt,
    String updatedAt,
    PublicInstanceProvisioning provisioning,
    PublicInstanceModel model,
    List<PublicInstanceModel> models,
    PublicInstanceModelAuth modelAuth,
    Map<String, Object> plugins,
    PublicWechatBinding wechatBinding
) {
  @JsonProperty("modelChain")
  public List<PublicInstanceModel> modelChain() {
    return models;
  }
}
