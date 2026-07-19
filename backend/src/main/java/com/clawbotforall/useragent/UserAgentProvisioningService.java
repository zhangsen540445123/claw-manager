package com.clawbotforall.useragent;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.miniapp.MiniappInstanceService;
import com.clawbotforall.wechat.OpenClawGatewayRpcService;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserAgentProvisioningService {
  private static final Pattern AGENT_ID = Pattern.compile("user_[0-9a-f]{32}");
  private static final Pattern OPENVIKING_USER_ID = Pattern.compile("wx_[0-9a-f]{32}");

  private final MiniappInstanceService instanceService;
  private final OpenClawGatewayRpcService gatewayRpcService;

  @Autowired
  public UserAgentProvisioningService(
      MiniappInstanceService instanceService,
      OpenClawGatewayRpcService gatewayRpcService
  ) {
    this.instanceService = instanceService;
    this.gatewayRpcService = gatewayRpcService;
  }

  public void ensure(
      String instanceId,
      String agentId,
      String openVikingUserId,
      String wechatAccountId,
      String wechatPeerId
  ) {
    String normalizedInstanceId = required(instanceId, "instanceId 不能为空。");
    String normalizedAgentId = normalize(agentId);
    String normalizedOpenVikingUserId = normalize(openVikingUserId);
    if (!AGENT_ID.matcher(normalizedAgentId).matches()
        || !OPENVIKING_USER_ID.matcher(normalizedOpenVikingUserId).matches()) {
      throw new IllegalArgumentException("用户 Agent 身份格式无效。");
    }
    String normalizedAccountId = required(wechatAccountId, "微信账号 ID 不能为空。");
    String normalizedPeerId = required(wechatPeerId, "微信用户 ID 不能为空。");
    InstanceEntity instance = instanceService.requireUsableApiInstance(normalizedInstanceId);
    gatewayRpcService.ensureUserAgent(
        instance,
        normalizedAgentId,
        normalizedOpenVikingUserId,
        normalizedAccountId,
        normalizedPeerId
    );
  }

  public void ensureApiBinding(
      String instanceId,
      String agentId,
      String openVikingUserId,
      String senderHash
  ) {
    String normalizedInstanceId = required(instanceId, "instanceId 不能为空。");
    String normalizedAgentId = normalize(agentId);
    String normalizedOpenVikingUserId = normalize(openVikingUserId);
    String normalizedSenderHash = required(senderHash, "API senderHash 不能为空。");
    if (!AGENT_ID.matcher(normalizedAgentId).matches()
        || !OPENVIKING_USER_ID.matcher(normalizedOpenVikingUserId).matches()) {
      throw new IllegalArgumentException("用户 Agent 身份格式无效。");
    }
    InstanceEntity instance = instanceService.requireUsableApiInstance(normalizedInstanceId);
    gatewayRpcService.ensureApiBinding(
        instance,
        normalizedAgentId,
        normalizedOpenVikingUserId,
        "api:" + normalizedSenderHash
    );
  }

  private static String required(String value, String message) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

}
