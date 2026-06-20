package com.clawbotforall.wechat;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.instance.InstanceProvisioningService;
import com.clawbotforall.instance.InstanceQueryService;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 通过微信插件登录结果启动并跟踪二维码绑定。
 */
@Service
public class WechatBindService {

  private static final Logger log = LoggerFactory.getLogger(WechatBindService.class);
  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\]）)>\"']+");
  private static final String LOGIN_EVENT_PREFIX = "__OPENCLAW_WECHAT_BIND__";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceCommandService commandService;
  private final InstanceFileService fileService;
  private final OpenClawRuntime openClawRuntime;
  private final InstanceQueryService queryService;
  private final InstanceEventPublisher eventPublisher;
  private final WechatAccountSyncService accountSyncService;
  private final WechatPluginService pluginService;
  private final OpenClawGatewayRpcService gatewayRpcService;
  private final ClawbotProperties properties;
  private final Map<String, RuntimeExecHandle> jobs = new ConcurrentHashMap<>();

  @Autowired
  public WechatBindService(
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceProvisioningService provisioningService,
      OpenClawRuntime openClawRuntime,
      InstanceQueryService queryService,
      InstanceEventPublisher eventPublisher,
      WechatAccountSyncService accountSyncService,
      WechatPluginService pluginService,
      OpenClawGatewayRpcService gatewayRpcService,
      ClawbotProperties properties
  ) {
    this(
        aggregateMapper,
        mutationMapper,
        commandService,
        fileService,
        provisioningService,
        openClawRuntime,
        queryService,
        eventPublisher,
        accountSyncService,
        pluginService,
        gatewayRpcService,
        properties,
        defaultWaitExecutor()
    );
  }

  WechatBindService(
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceProvisioningService provisioningService,
      OpenClawRuntime openClawRuntime,
      InstanceQueryService queryService,
      InstanceEventPublisher eventPublisher,
      WechatAccountSyncService accountSyncService,
      WechatPluginService pluginService,
      OpenClawGatewayRpcService gatewayRpcService,
      ClawbotProperties properties,
      Executor waitExecutor
  ) {
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.commandService = commandService;
    this.fileService = fileService;
    this.openClawRuntime = openClawRuntime;
    this.queryService = queryService;
    this.eventPublisher = eventPublisher;
    this.accountSyncService = accountSyncService;
    this.pluginService = pluginService;
    this.gatewayRpcService = gatewayRpcService;
    this.properties = properties;
  }

  /**
   * 启动 OpenClaw 微信 CLI 登录并在捕获二维码链接后返回。
   */
  public BindStartResult startBind(
      InstanceEntity instance,
      boolean forceRegenerate,
      String accountId
  ) {
    return startBind(instance, forceRegenerate, accountId, ignored -> {});
  }

  public BindStartResult startBind(
      InstanceEntity instance,
      boolean forceRegenerate,
      String accountId,
      BindCompletionCallback completionCallback
  ) {
    String normalizedAccountId = requireAccountId(accountId);
    requireReadyProvisioning(instance.getId());
    RuntimeState runtimeState = openClawRuntime.inspectInstance(instance);
    if (!runtimeState.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "请先启动该用户的 OpenClaw 容器，再进行微信绑定。");
    }
    if (!pluginService.isWechatPluginInstalled(instance)) {
      throw new ApiException(HttpStatus.CONFLICT, "请先在该 OpenClaw 实例安装微信插件，再生成扫码二维码。");
    }

    fileService.writeInstanceFiles(instance, commandService.listModels(instance.getId()));
    log.info(
        "开始微信插件扫码绑定任务：instanceId={}, accountId={}, forceRegenerate={}",
        instance.getId(),
        normalizedAccountId,
        forceRegenerate
    );

    CompletableFuture<BindStartResult> qrFuture = new CompletableFuture<>();
    StringBuilder output = new StringBuilder();
    AtomicReference<RuntimeExecHandle> handleRef = new AtomicReference<>();
    AtomicReference<BindCompletion> completionRef = new AtomicReference<>();
    String jobKey = jobKey(instance.getId(), normalizedAccountId);
    cancelExistingJob(jobKey);
    RuntimeExecHandle handle = openClawRuntime.startExec(
        instance,
        loginCommand(normalizedAccountId, forceRegenerate, bindTimeoutMs()),
        bindTimeoutMs(),
        Map.of("NODE_PATH", "/usr/local/lib/node_modules"),
        new RuntimeExecListener() {
          @Override
          public void onOutput(String chunk) {
            output.append(defaultString(chunk));
            JsonNode connected = latestLoginEvent(output.toString(), "connected");
            if (connected != null) {
              completionRef.set(bindCompletion(connected, normalizedAccountId));
            }
            JsonNode qr = latestLoginEvent(output.toString(), "qr");
            String qrLink = qr == null ? extractQrLink(output.toString()) : text(qr, "qrLink");
            if (qrLink.isBlank() || qrFuture.isDone()) {
              return;
            }
            qrFuture.complete(new BindStartResult(
                normalizedAccountId,
                qr == null ? null : text(qr, "sessionKey"),
                "link",
                "",
                qrLink,
                tailSnippet(output.toString(), 3000)
            ));
          }

          @Override
          public void onComplete(int exitCode) {
            jobs.remove(jobKey);
            if (exitCode == 0) {
              accountSyncService.syncInstanceAccounts(instance);
              BindCompletion completion = completionRef.get();
              if (completion == null) {
                JsonNode connected = latestLoginEvent(output.toString(), "connected");
                if (connected != null) {
                  completion = bindCompletion(connected, normalizedAccountId);
                }
              }
              log.info(
                  "微信插件扫码登录已确认：instanceId={}, requestedAccountId={}, actualAccountId={}, wechatUserId={}",
                  instance.getId(),
                  normalizedAccountId,
                  completion == null ? "" : completion.accountId(),
                  completion == null ? "" : completion.wechatUserId()
              );
              if (completion != null) {
                notifyBindCompleted(completionCallback, completion);
              }
              if (!qrFuture.isDone()) {
                qrFuture.completeExceptionally(new IllegalStateException("微信二维码生成失败：未捕获二维码链接。"));
              }
              return;
            }
            String message = tailSnippet(output.toString(), 3000);
            if (message.isBlank()) {
              message = "微信扫码登录命令退出：" + exitCode;
            }
            if (!qrFuture.isDone()) {
              qrFuture.completeExceptionally(new IllegalStateException(message));
            }
          }

          @Override
          public void onTimeout() {
            jobs.remove(jobKey);
            String message = "微信扫码二维码生成或扫码确认超时，请重新生成。";
            if (!qrFuture.isDone()) {
              qrFuture.completeExceptionally(new IllegalStateException(message));
            }
          }

          @Override
          public void onError(Throwable error) {
            jobs.remove(jobKey);
            String message = defaultString(error.getMessage()).isBlank() ? String.valueOf(error) : error.getMessage();
            if (!qrFuture.isDone()) {
              qrFuture.completeExceptionally(new IllegalStateException(message, error));
            }
          }
        }
    );
    handleRef.set(handle);
    jobs.put(jobKey, handle);

    try {
      return qrFuture.get(bindTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      RuntimeExecHandle current = handleRef.get();
      if (current != null) {
        current.cancel();
      }
      String message = "微信二维码生成失败：未捕获二维码链接。";
      throw new IllegalStateException(message, error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      String message = "微信二维码生成被中断。";
      throw new IllegalStateException(message, error);
    } catch (Exception error) {
      Throwable cause = error.getCause() == null ? error : error.getCause();
      String message = defaultString(cause.getMessage()).isBlank() ? String.valueOf(cause) : cause.getMessage();
      throw new IllegalStateException(message, cause);
    }
  }

  private void cancelExistingJob(String jobKey) {
    RuntimeExecHandle existing = jobs.remove(jobKey);
    if (existing != null && !existing.isCancelled()) {
      existing.cancel();
    }
  }

  private static String jobKey(String instanceId, String accountId) {
    return defaultString(instanceId).trim() + ":" + defaultString(accountId).trim();
  }

  private void notifyBindCompleted(BindCompletionCallback completionCallback, BindCompletion completion) {
    try {
      completionCallback.onConnected(completion);
    } catch (RuntimeException error) {
      log.warn("微信扫码完成回调失败：accountId={}, reason={}", completion.accountId(), error.getMessage());
    }
  }

  private void requireReadyProvisioning(String instanceId) {
    InstanceProvisioningEntity provisioning = aggregateMapper.listProvisioningByInstanceIds(List.of(instanceId))
        .stream()
        .findFirst()
        .orElse(null);
    if (provisioning != null && !"ready".equals(provisioning.getStatus())) {
      throw new ApiException(HttpStatus.CONFLICT, "实例尚未创建完成，请等待实例就绪后再绑定微信。");
    }
  }

  private long bindTimeoutMs() {
    return Math.max(1_000, properties.runtime().wechatBindTimeoutMs());
  }

  private static String requireAccountId(String accountId) {
    String normalized = defaultString(accountId).trim();
    if (normalized.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "微信账号标识不能为空。");
    }
    return normalized;
  }

  private static List<String> loginCommand(String accountId, boolean force, long timeoutMs) {
    return List.of(
        "node",
        "--input-type=module",
        "-e",
        loginScript(accountId, force, timeoutMs)
    );
  }

  private static String loginScript(String accountId, boolean force, long timeoutMs) {
    return """
        import fs from "node:fs";
        import path from "node:path";
        import { pathToFileURL } from "node:url";

        const requestedAccountId = %s;
        const force = %s;
        const timeoutMs = %d;
        const marker = %s;

        function emit(payload) {
          console.log(marker + JSON.stringify(payload));
        }

        function normalizeAccountId(value) {
          const trimmed = String(value ?? "").trim();
          if (!trimmed) return "default";
          const lower = trimmed.toLowerCase();
          if (/^[a-z0-9][a-z0-9_-]{0,63}$/i.test(trimmed)) return lower;
          const normalized = lower
            .replace(/[^a-z0-9_-]+/g, "-")
            .replace(/^-+/, "")
            .replace(/-+$/, "")
            .slice(0, 64);
          return normalized || "default";
        }

        function findPluginFile(relativePath) {
          const stateDir = process.env.OPENCLAW_STATE_DIR
            || path.join(process.env.OPENCLAW_HOME || "/var/lib/openclaw", ".openclaw");
          const projectsDir = path.join(stateDir, "npm", "projects");
          for (const project of fs.existsSync(projectsDir) ? fs.readdirSync(projectsDir) : []) {
            const candidate = path.join(
              projectsDir,
              project,
              "node_modules",
              "@tencent-weixin",
              "openclaw-weixin",
              relativePath
            );
            if (fs.existsSync(candidate)) return candidate;
          }
          throw new Error("Weixin plugin file not found: " + relativePath);
        }

        const loginQrPath = findPluginFile("dist/src/auth/login-qr.js");
        const accountsPath = findPluginFile("dist/src/auth/accounts.js");
        const {
          DEFAULT_ILINK_BOT_TYPE,
          startWeixinLoginWithQr,
          waitForWeixinLogin
        } = await import(pathToFileURL(loginQrPath).href);
        const {
          DEFAULT_BASE_URL,
          saveWeixinAccount,
          registerWeixinAccountId,
          triggerWeixinChannelReload
        } = await import(pathToFileURL(accountsPath).href);

        try {
          const startResult = await startWeixinLoginWithQr({
            accountId: requestedAccountId,
            apiBaseUrl: DEFAULT_BASE_URL,
            botType: DEFAULT_ILINK_BOT_TYPE,
            force,
            verbose: false
          });
          if (!startResult.qrcodeUrl) {
            emit({ type: "error", requestedAccountId, message: startResult.message || "QR login start failed" });
            process.exit(2);
          }

          emit({
            type: "qr",
            requestedAccountId,
            sessionKey: startResult.sessionKey,
            qrLink: startResult.qrcodeUrl,
            message: startResult.message || ""
          });

          const waitResult = await waitForWeixinLogin({
            sessionKey: startResult.sessionKey,
            apiBaseUrl: DEFAULT_BASE_URL,
            timeoutMs,
            botType: DEFAULT_ILINK_BOT_TYPE,
            verbose: false
          });

          if (waitResult.connected && waitResult.botToken && waitResult.accountId) {
            const actualAccountId = normalizeAccountId(waitResult.accountId);
            saveWeixinAccount(actualAccountId, {
              token: waitResult.botToken,
              baseUrl: waitResult.baseUrl,
              userId: waitResult.userId
            });
            registerWeixinAccountId(actualAccountId);
            await triggerWeixinChannelReload();
            emit({
              type: "connected",
              requestedAccountId,
              accountId: actualAccountId,
              rawAccountId: waitResult.accountId,
              wechatUserId: waitResult.userId || "",
              baseUrl: waitResult.baseUrl || DEFAULT_BASE_URL,
              message: waitResult.message || "",
              alreadyConnected: false
            });
            process.exit(0);
          }

          if (waitResult.alreadyConnected) {
            emit({
              type: "connected",
              requestedAccountId,
              accountId: requestedAccountId,
              rawAccountId: "",
              wechatUserId: "",
              baseUrl: DEFAULT_BASE_URL,
              message: waitResult.message || "",
              alreadyConnected: true
            });
            process.exit(0);
          }

          emit({ type: "error", requestedAccountId, message: waitResult.message || "QR login did not complete" });
          process.exit(2);
        } catch (error) {
          emit({ type: "error", requestedAccountId, message: error?.message || String(error) });
          throw error;
        }
        """.formatted(json(accountId), force ? "true" : "false", timeoutMs, json(LOGIN_EVENT_PREFIX));
  }

  private static String extractQrLink(String output) {
    Matcher matcher = URL_PATTERN.matcher(defaultString(output));
    String candidate = "";
    while (matcher.find()) {
      candidate = matcher.group();
    }
    return candidate.replaceAll("[,.;:，。；：]+$", "");
  }

  private static JsonNode latestLoginEvent(String output, String type) {
    JsonNode latest = null;
    for (String line : defaultString(output).split("\\R")) {
      String trimmed = line.trim();
      int index = trimmed.indexOf(LOGIN_EVENT_PREFIX);
      if (index < 0) {
        continue;
      }
      String json = trimmed.substring(index + LOGIN_EVENT_PREFIX.length()).trim();
      try {
        JsonNode event = JSON.readTree(json);
        if (type.equals(text(event, "type"))) {
          latest = event;
        }
      } catch (JsonProcessingException ignored) {
        // A Docker frame may split a line; the next output chunk will contain the full event.
      }
    }
    return latest;
  }

  private static BindCompletion bindCompletion(JsonNode event, String fallbackRequestedAccountId) {
    return new BindCompletion(
        firstNonBlank(text(event, "requestedAccountId"), fallbackRequestedAccountId),
        text(event, "accountId"),
        text(event, "rawAccountId"),
        text(event, "wechatUserId"),
        text(event, "baseUrl"),
        text(event, "message"),
        event != null && event.path("alreadyConnected").asBoolean(false)
    );
  }

  private static String text(JsonNode node, String field) {
    if (node == null || node.get(field) == null || node.get(field).isNull()) {
      return "";
    }
    return node.get(field).asText("");
  }

  private static String firstNonBlank(String first, String second) {
    String normalized = defaultString(first).trim();
    return normalized.isBlank() ? defaultString(second).trim() : normalized;
  }

  private static String json(String value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("微信扫码登录参数序列化失败。", error);
    }
  }

  private static Executor defaultWaitExecutor() {
    AtomicInteger sequence = new AtomicInteger();
    return Executors.newCachedThreadPool(task -> {
      Thread thread = new Thread(task, "wechat-bind-wait-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
  }

  private static String tailSnippet(String value, int maxLength) {
    String text = value == null ? "" : value.strip();
    if (text.length() <= maxLength) {
      return text;
    }
    return "..." + text.substring(text.length() - maxLength + 3);
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  public record BindStartResult(
      String accountId,
      String sessionKey,
      String qrMode,
      String qrPayload,
      String qrLink,
      String outputSnippet
  ) {}

  public record BindCompletion(
      String requestedAccountId,
      String accountId,
      String rawAccountId,
      String wechatUserId,
      String baseUrl,
      String message,
      boolean alreadyConnected
  ) {}

  @FunctionalInterface
  public interface BindCompletionCallback {
    void onConnected(BindCompletion completion);
  }

  private record WechatState(
      String status,
      String qrMode,
      String qrPayload,
      String qrLink,
      String outputSnippet
  ) {
    static WechatState starting(String outputSnippet) {
      return new WechatState("starting", null, "", "", outputSnippet);
    }

    static WechatState waitingScan(String qrMode, String qrPayload, String qrLink, String outputSnippet) {
      return new WechatState("waiting_scan", qrMode, qrPayload, qrLink, outputSnippet);
    }

    static WechatState connected(String outputSnippet) {
      return new WechatState("connected", null, "", "", outputSnippet);
    }

    static WechatState error(String outputSnippet) {
      return new WechatState("error", null, "", "", outputSnippet);
    }

    boolean hasQrPayload() {
      return !defaultString(qrPayload).isBlank() || !defaultString(qrLink).isBlank();
    }
  }
}
