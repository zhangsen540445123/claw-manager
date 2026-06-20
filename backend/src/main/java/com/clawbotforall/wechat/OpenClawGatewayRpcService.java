package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * 在 runner 容器内部复用 OpenClaw 自己的 Gateway backend 调用，避免外部设备配对权限问题。
 */
@Service
public class OpenClawGatewayRpcService {

  private static final long START_CHANNEL_TIMEOUT_MS = 15_000;

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
    Set<String> normalizedAccountIds = new LinkedHashSet<>();
    for (String accountId : accountIds == null ? List.<String>of() : accountIds) {
      String normalized = defaultString(accountId).trim();
      if (!normalized.isBlank()) {
        normalizedAccountIds.add(normalized);
      }
    }
    if (normalizedAccountIds.isEmpty()) {
      startAccount(instance, null);
      return;
    }
    for (String accountId : normalizedAccountIds) {
      startAccount(instance, accountId);
    }
  }

  private void startAccount(InstanceEntity instance, String accountId) {
    CompletableFuture<Integer> exit = new CompletableFuture<>();
    StringBuilder output = new StringBuilder();
    openClawRuntime.startExec(
        instance,
        List.of("node", "--input-type=module", "-e", startScript(accountId)),
        START_CHANNEL_TIMEOUT_MS,
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
            exit.completeExceptionally(new TimeoutException("OpenClaw channels.start 执行超时。"));
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
      throw new IllegalStateException("OpenClaw channels.start 被中断。", error);
    } catch (Exception error) {
      throw new IllegalStateException("OpenClaw channels.start 调用失败：" + message(error), error);
    }
    if (exitCode != 0) {
      throw new IllegalStateException("OpenClaw channels.start 退出码 " + exitCode + "：" + tail(output.toString()));
    }
  }

  private String startScript(String accountId) {
    String accountLiteral;
    try {
      accountLiteral = accountId == null || accountId.isBlank() ? "undefined" : objectMapper.writeValueAsString(accountId);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("微信账号 ID 序列化失败。", error);
    }
    return """
        import { c as callGateway } from "/usr/local/lib/node_modules/openclaw/dist/call-BlqKbSL2.js";
        import { i as GATEWAY_CLIENT_NAMES, r as GATEWAY_CLIENT_MODES } from "/usr/local/lib/node_modules/openclaw/dist/client-info-CcqJJIan.js";
        const accountId = %s;
        const params = { channel: "openclaw-weixin" };
        if (accountId) params.accountId = accountId;
        const result = await callGateway({
          method: "channels.start",
          params,
          mode: GATEWAY_CLIENT_MODES.BACKEND,
          clientName: GATEWAY_CLIENT_NAMES.GATEWAY_CLIENT,
          deviceIdentity: null,
          requireLocalBackendSharedAuth: true,
          timeoutMs: 8000
        });
        console.log(JSON.stringify(result));
        if (!result?.started) process.exitCode = 2;
        """.formatted(accountLiteral);
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
}
