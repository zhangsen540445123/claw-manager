package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 在 runner 容器内部复用 OpenClaw 自己的 Gateway backend 调用，避免外部设备配对权限问题。
 */
@Service
public class OpenClawGatewayRpcService {

  private static final Logger log = LoggerFactory.getLogger(OpenClawGatewayRpcService.class);
  private static final long START_CHANNEL_TIMEOUT_MS = 30_000;
  private static final String WECHAT_CHANNEL_ID = "openclaw-weixin";
  private static final String API_CHANNEL_ID = "claw-manager-api";
  private static final String API_ACCOUNT_ID = "global";
  private static final long API_CHANNEL_START_TIMEOUT_MS = 45_000;
  private static final long API_CHANNEL_START_RETRY_DELAY_MS = 500;

  private final OpenClawRuntime openClawRuntime;
  private final ObjectMapper objectMapper;

  public OpenClawGatewayRpcService(OpenClawRuntime openClawRuntime, ObjectMapper objectMapper) {
    this.openClawRuntime = openClawRuntime;
    this.objectMapper = objectMapper;
  }

  public void startWechatChannel(InstanceEntity instance) {
    startWechatChannel(instance, List.of());
  }

  public void startWechatChannel(InstanceEntity instance, List<String> accountIds) {
    Set<String> normalizedAccountIds = normalizeAccountIds(accountIds);
    if (normalizedAccountIds.isEmpty()) {
      startAccount(instance, WECHAT_CHANNEL_ID, null);
      return;
    }
    for (String accountId : normalizedAccountIds) {
      startAccount(instance, WECHAT_CHANNEL_ID, accountId);
    }
  }

  public void startApiChannel(InstanceEntity instance) {
    log.debug("API Channel monitor is auto-started by the plugin runtime: instanceId={}", instance.getId());
  }


  /**
   * 通过 OpenClaw 官方 Gateway 管理 RPC 轮换指定 Session。
   *
   * <p>只传递 Session Key，不附加 reason，也不直接改写或删除 Session 文件。</p>
   */
  public void resetSession(InstanceEntity instance, String sessionKey) {
    String normalizedSessionKey = defaultString(sessionKey).trim();
    if (normalizedSessionKey.isEmpty()) {
      throw new IllegalArgumentException("Session Key 不能为空。");
    }
    JsonNode result = runGatewayJsonMethodRaw(
        instance,
        "sessions.reset",
        Map.of("key", normalizedSessionKey)
    );
    requireTrue("sessions.reset", result, "ok");
    log.info(
        "OpenClaw Session 已通过官方 RPC 轮换: instanceId={} sessionHash={}",
        instance.getId(),
        WechatLogSanitizer.identityHashPreview(normalizedSessionKey)
    );
  }

  public void ensureUserAgent(
      InstanceEntity instance,
      String agentId,
      String openVikingUserId,
      String wechatAccountId,
      String wechatPeerId
  ) {
    runGatewayJsonMethod(instance, "claw-manager-api.ensure-user-agent", Map.of(
        "agentId", agentId,
        "openVikingUserId", openVikingUserId,
        "wechatAccountId", wechatAccountId,
        "wechatPeerId", wechatPeerId
    ), "wechatBindingCreated");
  }

  public void ensureApiBinding(
      InstanceEntity instance,
      String agentId,
      String openVikingUserId,
      String apiPeerId
  ) {
    runGatewayJsonMethod(instance, "claw-manager-api.ensure-api-binding", Map.of(
        "agentId", agentId,
        "openVikingUserId", openVikingUserId,
        "apiPeerId", apiPeerId
    ), "apiBindingCreated");
  }

  public ReplaceUserAgentResult replaceUserAgent(
      InstanceEntity instance,
      String newAgentId,
      String openVikingUserId,
      String wechatAccountId,
      String wechatPeerId,
      String oldAgentId,
      List<String> apiPeerIds
  ) {
    Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("newAgentId", newAgentId);
    params.put("openVikingUserId", openVikingUserId);
    params.put("wechatAccountId", wechatAccountId);
    params.put("wechatPeerId", wechatPeerId);
    params.put("oldAgentId", oldAgentId);
    params.put("apiPeerIds", apiPeerIds == null ? List.of() : apiPeerIds);
    JsonNode result = runGatewayJsonMethodRaw(instance, "claw-manager-api.replace-user-agent", params);
    return new ReplaceUserAgentResult(
        result.path("persisted").asBoolean(false),
        result.path("runtimeApplied").asBoolean(false),
        result.path("bindingCreated").asBoolean(false),
        objectMapper.convertValue(result.path("displacedAgentIds"), new TypeReference<List<String>>() {}),
        objectMapper.convertValue(result.path("conflictingBindings"), new TypeReference<List<Map<String, Object>>>() {})
    );
  }

  public DeleteUserAgentResult deleteUserAgent(
      InstanceEntity instance,
      String agentId,
      List<String> wechatAccountIds,
      List<String> wechatPeerIds,
      List<String> apiPeerIds,
      List<String> protectedAgentIds
  ) {
    Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("agentId", agentId);
    params.put("wechatAccountIds", wechatAccountIds == null ? List.of() : wechatAccountIds);
    params.put("wechatPeerIds", wechatPeerIds == null ? List.of() : wechatPeerIds);
    params.put("apiPeerIds", apiPeerIds == null ? List.of() : apiPeerIds);
    params.put("protectedAgentIds", protectedAgentIds == null ? List.of() : protectedAgentIds);
    JsonNode result = runGatewayJsonMethodRaw(instance, "claw-manager-api.delete-user-agent", params);
    return new DeleteUserAgentResult(
        result.path("persisted").asBoolean(false),
        result.path("runtimeApplied").asBoolean(false),
        result.path("agentRemoved").asBoolean(false),
        objectMapper.convertValue(result.path("removedBindings"), new TypeReference<List<Map<String, Object>>>() {}),
        objectMapper.convertValue(result.path("conflictingBindings"), new TypeReference<List<Map<String, Object>>>() {})
    );
  }

  public void stopWechatChannel(InstanceEntity instance, List<String> accountIds) {
    Set<String> normalizedAccountIds = normalizeAccountIds(accountIds);
    if (normalizedAccountIds.isEmpty()) {
      stopAccount(instance, WECHAT_CHANNEL_ID, null);
      return;
    }
    for (String accountId : normalizedAccountIds) {
      stopAccount(instance, WECHAT_CHANNEL_ID, accountId);
    }
  }

  public void restartWechatChannel(InstanceEntity instance, List<String> accountIds) {
    Set<String> normalizedAccountIds = normalizeAccountIds(accountIds);
    if (normalizedAccountIds.isEmpty()) {
      startAccount(instance, WECHAT_CHANNEL_ID, null);
      return;
    }
    for (String accountId : normalizedAccountIds) {
      try {
        stopAccount(instance, WECHAT_CHANNEL_ID, accountId);
      } catch (RuntimeException error) {
        log.warn(
            "OpenClaw channels.stop 失败，将继续 start：instanceId={}, accountHash={}, reason={}",
            instance.getId(),
            WechatLogSanitizer.identityHashPreview(accountId),
            error.getMessage()
        );
      }
      startAccount(instance, WECHAT_CHANNEL_ID, accountId);
    }
  }

  private Set<String> normalizeAccountIds(List<String> accountIds) {
    Set<String> normalizedAccountIds = new LinkedHashSet<>();
    for (String accountId : accountIds == null ? List.<String>of() : accountIds) {
      String normalized = defaultString(accountId).trim();
      if (!normalized.isBlank()) {
        normalizedAccountIds.add(normalized);
      }
    }
    return normalizedAccountIds;
  }

  private void startAccount(InstanceEntity instance, String channelId, String accountId) {
    runChannelOperation(instance, "channels.start", channelId, accountId, true);
  }

  private void stopAccount(InstanceEntity instance, String channelId, String accountId) {
    runChannelOperation(instance, "channels.stop", channelId, accountId, false);
  }

  private void runChannelOperation(InstanceEntity instance, String method, String channelId, String accountId, boolean requireStarted) {
    runGatewayScript(instance, method, operationScript(method, channelId, accountId, requireStarted));
  }

  private String runGatewayScript(InstanceEntity instance, String method, String script) {
    CompletableFuture<Integer> exit = new CompletableFuture<>();
    StringBuilder output = new StringBuilder();
    openClawRuntime.startExec(
        instance,
        List.of("node", "--input-type=module", "-e", script),
        START_CHANNEL_TIMEOUT_MS,
        Map.of(),
        new RuntimeExecListener() {
          @Override
          public void onOutput(String chunk) {
            output.append(defaultString(chunk));
          }

          @Override
          public void onComplete(int exitCode) {
            exit.complete(exitCode);
          }

          @Override
          public void onTimeout() {
            exit.completeExceptionally(new TimeoutException("OpenClaw " + method + " 执行超时。"));
          }

          @Override
          public void onError(Throwable error) {
            exit.completeExceptionally(error);
          }
        }
    );
    int exitCode;
    try {
      exitCode = exit.get(START_CHANNEL_TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OpenClaw " + method + " 被中断。", error);
    } catch (Exception error) {
      throw new IllegalStateException("OpenClaw " + method + " 调用失败：" + message(error), error);
    }
    if (exitCode != 0) {
      throw new IllegalStateException("OpenClaw " + method + " 退出码 " + exitCode + "：" + tail(output.toString()));
    }
    return output.toString();
  }

  private void runGatewayJsonMethod(
      InstanceEntity instance,
      String method,
      Map<String, Object> params,
      String bindingCreatedField
  ) {
    JsonNode result = runGatewayJsonMethodRaw(instance, method, params);
    requireTrue(method, result, "persisted");
    requireTrue(method, result, "runtimeApplied");
    if (!result.has(bindingCreatedField) || !result.get(bindingCreatedField).isBoolean()) {
      throw new IllegalStateException("OpenClaw " + method + " 响应缺少布尔字段 " + bindingCreatedField + "。");
    }
  }

  private JsonNode runGatewayJsonMethodRaw(InstanceEntity instance, String method, Map<String, Object> params) {
    String methodLiteral;
    String paramsLiteral;
    try {
      methodLiteral = objectMapper.writeValueAsString(method);
      paramsLiteral = objectMapper.writeValueAsString(params);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Gateway RPC 参数序列化失败。", error);
    }
    String script = """
        import { c as callGateway } from "/usr/local/lib/node_modules/openclaw/dist/call-BlqKbSL2.js";
        import { i as GATEWAY_CLIENT_NAMES, r as GATEWAY_CLIENT_MODES } from "/usr/local/lib/node_modules/openclaw/dist/client-info-CcqJJIan.js";
        const result = await callGateway({
          method: %s,
          params: %s,
          mode: GATEWAY_CLIENT_MODES.BACKEND,
          clientName: GATEWAY_CLIENT_NAMES.GATEWAY_CLIENT,
          deviceIdentity: null,
          requireLocalBackendSharedAuth: true,
          scopes: ["operator.admin"],
          timeoutMs: 15000
        });
        console.log(JSON.stringify(result));
        """.formatted(methodLiteral, paramsLiteral);
    return parseLastJsonLine(method, runGatewayScript(instance, method, script));
  }

  private JsonNode parseLastJsonLine(String method, String output) {
    String[] lines = defaultString(output).split("\\R");
    for (int index = lines.length - 1; index >= 0; index--) {
      String line = lines[index].trim();
      if (line.isEmpty()) {
        continue;
      }
      try {
        return objectMapper.readTree(line);
      } catch (JsonProcessingException ignored) {
        // Gateway may emit diagnostic lines before the final JSON response.
      }
    }
    throw new IllegalStateException("OpenClaw " + method + " 未返回有效 JSON 响应。");
  }

  private void requireTrue(String method, JsonNode result, String field) {
    if (!result.path(field).isBoolean() || !result.path(field).booleanValue()) {
      throw new IllegalStateException("OpenClaw " + method + " 响应字段 " + field + " 必须为 true。");
    }
  }

  private String operationScript(String method, String channelId, String accountId, boolean requireStarted) {
    String methodLiteral;
    String channelLiteral;
    String accountLiteral;
    try {
      methodLiteral = objectMapper.writeValueAsString(method);
      channelLiteral = objectMapper.writeValueAsString(defaultString(channelId).trim());
      accountLiteral = accountId == null || accountId.isBlank() ? "undefined" : objectMapper.writeValueAsString(accountId);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("微信通道操作参数序列化失败。", error);
    }
    return """
        import { c as callGateway } from "/usr/local/lib/node_modules/openclaw/dist/call-BlqKbSL2.js";
        import { i as GATEWAY_CLIENT_NAMES, r as GATEWAY_CLIENT_MODES } from "/usr/local/lib/node_modules/openclaw/dist/client-info-CcqJJIan.js";
        const method = %s;
        const channel = %s;
        const accountId = %s;
        const params = { channel };
        if (accountId) params.accountId = accountId;
        const scopes = channel === "claw-manager-api" ? ["operator.admin"] : undefined;
        const result = await callGateway({
          method,
          params,
          mode: GATEWAY_CLIENT_MODES.BACKEND,
          clientName: GATEWAY_CLIENT_NAMES.GATEWAY_CLIENT,
          deviceIdentity: null,
          requireLocalBackendSharedAuth: true,
          ...(scopes ? { scopes } : {}),
          timeoutMs: 8000
        });
        console.log(JSON.stringify(result));
        if (%s && !result?.started) process.exitCode = 2;
        """.formatted(methodLiteral, channelLiteral, accountLiteral, requireStarted ? "true" : "false");
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  private static String message(Throwable error) {
    Throwable cause = error.getCause() == null ? error : error.getCause();
    String message = cause.getMessage();
    return message == null || message.isBlank() ? String.valueOf(cause) : message;
  }

  private static String tail(String value) {
    String normalized = defaultString(value).trim();
    if (normalized.length() <= 1000) {
      return normalized;
    }
    return normalized.substring(normalized.length() - 1000);
  }

  private static boolean isRetryableApiChannelStartError(Throwable error) {
    String message = message(error).toLowerCase();
    return message.contains("unknown channel")
        || message.contains("unknown method")
        || message.contains("gateway")
        || message.contains("timeout")
        || message.contains("timed out")
        || message.contains("超时");
  }

  private static void sleepBeforeApiChannelRetry() {
    try {
      Thread.sleep(API_CHANNEL_START_RETRY_DELAY_MS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OpenClaw API Channel 启动重试被中断。", error);
    }
  }
  public record DeleteUserAgentResult(
      boolean persisted,
      boolean runtimeApplied,
      boolean agentRemoved,
      List<Map<String, Object>> removedBindings,
      List<Map<String, Object>> conflictingBindings
  ) {
    public DeleteUserAgentResult {
      removedBindings = removedBindings == null ? List.of() : List.copyOf(removedBindings);
      conflictingBindings = conflictingBindings == null ? List.of() : List.copyOf(conflictingBindings);
    }

    public boolean success() {
      return persisted && runtimeApplied && agentRemoved && conflictingBindings.isEmpty();
    }
  }

  public record ReplaceUserAgentResult(
      boolean persisted,
      boolean runtimeApplied,
      boolean bindingCreated,
      List<String> displacedAgentIds,
      List<Map<String, Object>> conflictingBindings
  ) {
    public ReplaceUserAgentResult {
      displacedAgentIds = displacedAgentIds == null ? List.of() : List.copyOf(displacedAgentIds);
      conflictingBindings = conflictingBindings == null ? List.of() : List.copyOf(conflictingBindings);
    }

    public boolean success() {
      return persisted && runtimeApplied && bindingCreated && conflictingBindings.isEmpty();
    }
  }

}
