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
import com.clawbotforall.instance.InstanceWechatBindingEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
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
 * 通过 OpenClaw CLI 启动并跟踪微信二维码绑定。
 */
@Service
public class WechatBindService {

  private static final Logger log = LoggerFactory.getLogger(WechatBindService.class);
  private static final List<String> WECHAT_LOGIN_COMMAND = List.of(
      "openclaw",
      "channels",
      "login",
      "--channel",
      "openclaw-weixin"
  );
  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\]）)>\"']+");

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
    String normalizedAccountId = requireAccountId(accountId);
    requireReadyProvisioning(instance.getId());
    RuntimeState runtimeState = openClawRuntime.inspectInstance(instance);
    if (!runtimeState.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "请先启动该用户的 OpenClaw 容器，再进行微信绑定。");
    }
    if (!pluginService.isWechatPluginInstalled(instance)) {
      throw new ApiException(HttpStatus.CONFLICT, "请先在该 OpenClaw 实例安装微信插件，再生成扫码二维码。");
    }

    cancelExistingJob(instance.getId());
    fileService.writeInstanceFiles(instance, commandService.listModels(instance.getId()));
    patchBinding(instance, WechatState.starting("正在准备微信扫码绑定，请稍候。"));
    log.info(
        "开始微信 CLI 扫码绑定任务：instanceId={}, accountId={}, forceRegenerate={}",
        instance.getId(),
        normalizedAccountId,
        forceRegenerate
    );

    CompletableFuture<BindStartResult> qrFuture = new CompletableFuture<>();
    StringBuilder output = new StringBuilder();
    AtomicReference<RuntimeExecHandle> handleRef = new AtomicReference<>();
    RuntimeExecHandle handle = openClawRuntime.startExec(
        instance,
        WECHAT_LOGIN_COMMAND,
        bindTimeoutMs(),
        Map.of(),
        new RuntimeExecListener() {
          @Override
          public void onOutput(String chunk) {
            output.append(defaultString(chunk));
            String qrLink = extractQrLink(output.toString());
            if (qrLink.isBlank() || qrFuture.isDone()) {
              return;
            }
            WechatState waiting = WechatState.waitingScan(
                "link",
                "",
                qrLink,
                "请使用微信扫描二维码完成绑定。"
            );
            patchBinding(instance, waiting);
            qrFuture.complete(new BindStartResult(
                normalizedAccountId,
                null,
                "link",
                "",
                qrLink,
                tailSnippet(output.toString(), 3000)
            ));
          }

          @Override
          public void onComplete(int exitCode) {
            jobs.remove(instance.getId());
            if (exitCode == 0) {
              accountSyncService.syncInstanceAccounts(instance);
              startWechatChannel(instance, accountSyncService.readRawAccountIds(instance));
              patchBinding(instance, WechatState.connected("微信绑定成功，可以使用微信连接 OpenClaw。"));
              log.info("微信 CLI 扫码登录已确认：instanceId={}", instance.getId());
              if (!qrFuture.isDone()) {
                qrFuture.completeExceptionally(new IllegalStateException("微信二维码生成失败：未捕获二维码链接。"));
              }
              return;
            }
            String message = tailSnippet(output.toString(), 3000);
            if (message.isBlank()) {
              message = "微信扫码登录命令退出：" + exitCode;
            }
            patchBinding(instance, WechatState.error(message));
            if (!qrFuture.isDone()) {
              qrFuture.completeExceptionally(new IllegalStateException(message));
            }
          }

          @Override
          public void onTimeout() {
            jobs.remove(instance.getId());
            String message = "微信扫码二维码生成或扫码确认超时，请重新生成。";
            patchBinding(instance, WechatState.error(message));
            if (!qrFuture.isDone()) {
              qrFuture.completeExceptionally(new IllegalStateException(message));
            }
          }

          @Override
          public void onError(Throwable error) {
            jobs.remove(instance.getId());
            String message = defaultString(error.getMessage()).isBlank() ? String.valueOf(error) : error.getMessage();
            patchBinding(instance, WechatState.error(tailSnippet(message, 3000)));
            if (!qrFuture.isDone()) {
              qrFuture.completeExceptionally(new IllegalStateException(message, error));
            }
          }
        }
    );
    handleRef.set(handle);
    jobs.put(instance.getId(), handle);

    try {
      return qrFuture.get(bindTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      RuntimeExecHandle current = handleRef.get();
      if (current != null) {
        current.cancel();
      }
      String message = "微信二维码生成失败：未捕获二维码链接。";
      patchBinding(instance, WechatState.error(message));
      throw new IllegalStateException(message, error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      String message = "微信二维码生成被中断。";
      patchBinding(instance, WechatState.error(message));
      throw new IllegalStateException(message, error);
    } catch (Exception error) {
      Throwable cause = error.getCause() == null ? error : error.getCause();
      String message = defaultString(cause.getMessage()).isBlank() ? String.valueOf(cause) : cause.getMessage();
      throw new IllegalStateException(message, cause);
    }
  }

  private void cancelExistingJob(String instanceId) {
    RuntimeExecHandle existing = jobs.remove(instanceId);
    if (existing != null && !existing.isCancelled()) {
      existing.cancel();
    }
  }

  private void startWechatChannel(InstanceEntity instance, List<String> accountIds) {
    try {
      gatewayRpcService.startWechatChannel(instance, accountIds);
      log.info("微信通道已热启动：instanceId={}, accountIds={}", instance.getId(), accountIds);
    } catch (RuntimeException error) {
      log.warn("微信通道热启动失败，将等待 OpenClaw 健康检查自动拉起：instanceId={}, reason={}", instance.getId(), error.getMessage());
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

  private void patchBinding(InstanceEntity instance, WechatState state) {
    InstanceWechatBindingEntity current = aggregateMapper.listWechatBindingByInstanceIds(List.of(instance.getId()))
        .stream()
        .findFirst()
        .orElse(null);
    Instant now = Instant.now();
    InstanceWechatBindingEntity next = new InstanceWechatBindingEntity();
    next.setInstanceId(instance.getId());
    next.setStatus(state.status());
    next.setUpdatedAt(now.toString());
    if ("waiting_scan".equals(state.status()) && state.hasQrPayload()) {
      next.setQrMode(state.qrMode());
      next.setQrPayload(defaultString(state.qrPayload()));
      next.setQrLink(defaultString(state.qrLink()));
      next.setQrExpiresAt(resolveQrExpiresAt(current, state, now));
    } else {
      next.setQrMode(null);
      next.setQrPayload("");
      next.setQrLink("");
      next.setQrExpiresAt(null);
    }
    next.setOutputSnippet(state.outputSnippet());
    boolean connected = "connected".equals(state.status());
    next.setRuntimeReady(connected);
    next.setRuntimeStatus(runtimeStatus(state.status()));
    next.setRuntimeMessage(connected ? "" : state.outputSnippet());
    next.setRuntimeUpdatedAt(now.toString());
    mutationMapper.updateWechatBinding(next);
    publishCurrent(instance.getId());
  }

  private String resolveQrExpiresAt(InstanceWechatBindingEntity current, WechatState state, Instant now) {
    if (current != null
        && "waiting_scan".equals(current.getStatus())
        && sameQr(current, state)
        && current.getQrExpiresAt() != null
        && !current.getQrExpiresAt().isBlank()) {
      return current.getQrExpiresAt();
    }
    return now.plusMillis(Math.max(1, properties.runtime().wechatQrTtlMs())).toString();
  }

  private void publishCurrent(String instanceId) {
    queryService.findPublicInstance(instanceId, null).ifPresent(publicInstance -> {
      eventPublisher.publishWechatBindingUpdated(instanceId, publicInstance.wechatBinding());
      eventPublisher.publishInstanceUpdated(publicInstance);
    });
  }

  private long bindTimeoutMs() {
    return Math.max(1_000, properties.runtime().wechatBindTimeoutMs());
  }

  private static boolean sameQr(InstanceWechatBindingEntity current, WechatState state) {
    return defaultString(current.getQrMode()).equals(defaultString(state.qrMode()))
        && defaultString(current.getQrPayload()).equals(defaultString(state.qrPayload()))
        && defaultString(current.getQrLink()).equals(defaultString(state.qrLink()));
  }

  private static String runtimeStatus(String status) {
    return switch (defaultString(status)) {
      case "connected" -> "ready";
      case "error" -> "error";
      case "starting", "waiting_scan", "scanned" -> "pending";
      default -> "idle";
    };
  }

  private static String requireAccountId(String accountId) {
    String normalized = defaultString(accountId).trim();
    if (normalized.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "微信账号标识不能为空。");
    }
    return normalized;
  }

  private static String extractQrLink(String output) {
    Matcher matcher = URL_PATTERN.matcher(defaultString(output));
    String candidate = "";
    while (matcher.find()) {
      candidate = matcher.group();
    }
    return candidate.replaceAll("[,.;:，。；：]+$", "");
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
