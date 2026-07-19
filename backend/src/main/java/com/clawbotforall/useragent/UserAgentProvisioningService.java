package com.clawbotforall.useragent;

import com.clawbotforall.externalapi.ExternalApiQueueService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.miniapp.MiniappInstanceService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class UserAgentProvisioningService {
  private static final Logger log = LoggerFactory.getLogger(UserAgentProvisioningService.class);
  private static final Pattern AGENT_ID = Pattern.compile("user_[0-9a-f]{32}");
  private static final Pattern OPENVIKING_USER_ID = Pattern.compile("wx_[0-9a-f]{32}");

  private final MiniappInstanceService instanceService;
  private final ExternalApiQueueService queueService;
  private final Executor executor;

  @Autowired
  public UserAgentProvisioningService(
      MiniappInstanceService instanceService,
      ExternalApiQueueService queueService,
      @Qualifier(UserAgentExecutorConfiguration.EXECUTOR_BEAN_NAME) Executor executor
  ) {
    this.instanceService = instanceService;
    this.queueService = queueService;
    this.executor = executor;
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
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("operation", "ensure_user_agent");
    payload.put("requestId", "ensure_" + UUID.randomUUID().toString().replace("-", ""));
    payload.put("agentId", normalizedAgentId);
    payload.put("openVikingUserId", normalizedOpenVikingUserId);
    payload.put("wechatAccountId", normalizedAccountId);
    payload.put("wechatPeerId", normalizedPeerId);
    queueService.sendApiChannelMessage(instance, payload);
  }

  public void ensureAsync(
      String instanceId,
      String agentId,
      String openVikingUserId,
      String wechatAccountId,
      String wechatPeerId
  ) {
    try {
      executor.execute(() -> {
        try {
          ensure(instanceId, agentId, openVikingUserId, wechatAccountId, wechatPeerId);
        } catch (RuntimeException error) {
          logAsyncFailure(instanceId, agentId, wechatAccountId, wechatPeerId, error);
        }
      });
    } catch (RuntimeException error) {
      logAsyncFailure(instanceId, agentId, wechatAccountId, wechatPeerId, error);
    }
  }

  private void logAsyncFailure(
      String instanceId,
      String agentId,
      String wechatAccountId,
      String wechatPeerId,
      RuntimeException error
  ) {
    log.warn(
        "userAgent.provisioning.asyncFailed instanceId={} agentIdPreview={} accountIdPresent={} peerIdPresent={} errorType={}",
        normalize(instanceId),
        preview(agentId),
        present(wechatAccountId),
        present(wechatPeerId),
        error.getClass().getSimpleName()
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

  private static String preview(String value) {
    String normalized = normalize(value);
    if (normalized.length() <= 12) {
      return normalized.isBlank() ? "-" : normalized;
    }
    return normalized.substring(0, 8) + "..." + normalized.substring(normalized.length() - 4);
  }

  private static String present(String value) {
    return normalize(value).isBlank() ? "absent" : "present";
  }
}
