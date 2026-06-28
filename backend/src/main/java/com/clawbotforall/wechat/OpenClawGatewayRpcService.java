package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        log.warn("OpenClaw channels.stop 失败，将继续 start：instanceId={}, accountId={}, reason={}", instance.getId(), accountId, error.getMessage());
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

  private void runGatewayScript(InstanceEntity instance, String method, String script) {
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
}
