package com.clawbotforall.wechat;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceProvisioningService;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.instance.InstanceQueryService;
import com.clawbotforall.instance.InstanceWechatBindingEntity;
import com.clawbotforall.instance.PublicInstance;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 在 OpenClaw 容器内启动并跟踪微信二维码绑定。
 */
@Service
public class WechatBindService {

  private static final Logger log = LoggerFactory.getLogger(WechatBindService.class);

  private static final String WECHAT_CHANNEL_ID = "openclaw-weixin";
  private static final String WECHAT_PLUGIN_SPEC = "@tencent-weixin/openclaw-weixin";
  private static final String RUNTIME_INIT_MESSAGE = "微信绑定已完成，正在确认通道状态，请稍候。";
  private static final String RUNTIME_RESTARTING_MESSAGE = "微信已绑定成功，OpenClaw 正在重启微信通道，通常需要 1-3 分钟，请稍后再使用。";
  private static final Pattern DATA_URL = Pattern.compile("data:image/[a-zA-Z+.-]+;base64,[A-Za-z0-9+/=]+");
  private static final Pattern DIRECT_LINK = Pattern.compile("https://[^\\s\"'<>]+");

  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceCommandService commandService;
  private final InstanceFileService fileService;
  private final InstanceProvisioningService provisioningService;
  private final OpenClawRuntime openClawRuntime;
  private final InstanceQueryService queryService;
  private final InstanceEventPublisher eventPublisher;
  private final WechatAccountSyncService accountSyncService;
  private final ClawbotProperties properties;
  private final Map<String, RuntimeExecHandle> jobs = new ConcurrentHashMap<>();

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
      ClawbotProperties properties
  ) {
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.commandService = commandService;
    this.fileService = fileService;
    this.provisioningService = provisioningService;
    this.openClawRuntime = openClawRuntime;
    this.queryService = queryService;
    this.eventPublisher = eventPublisher;
    this.accountSyncService = accountSyncService;
    this.properties = properties;
  }

  /**
   * 在实例容器内启动微信二维码绑定命令。
   */

  public void startBind(
      InstanceEntity instance,
      boolean forceRegenerate
  ) {
    requireReadyProvisioning(instance.getId());
    RuntimeState runtimeState = openClawRuntime.inspectInstance(instance);
    if (!runtimeState.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "请先启动该用户的 OpenClaw 容器，再进行微信绑定。");
    }

    RuntimeExecHandle existing = jobs.get(instance.getId());
    boolean qrExpired = isCurrentQrExpired(instance.getId());
    if (existing != null && !forceRegenerate && !qrExpired) {
      log.info("微信绑定任务已在进行中，复用当前二维码状态：instanceId={}", instance.getId());
      publishCurrent(instance.getId());
      return;
    }
    if (existing != null) {
      log.info("取消旧微信绑定任务并重新生成二维码：instanceId={}, forceRegenerate={}, qrExpired={}", instance.getId(), forceRegenerate, qrExpired);
      existing.cancel();
      jobs.remove(instance.getId());
    }

    fileService.writeInstanceFiles(instance, commandService.listModels(instance.getId()));
    patchBinding(instance, WechatState.starting("正在准备微信扫码绑定，请稍候。"));
    log.info("开始微信扫码绑定任务：instanceId={}, forceRegenerate={}", instance.getId(), forceRegenerate);

    StringBuilder output = new StringBuilder();
    RuntimeExecHandle handle = openClawRuntime.startExec(
        instance,
        buildWechatCommand(),
        properties.runtime().wechatBindTimeoutMs(),
        Map.of(),
        new RuntimeExecListener() {
          /**
           * 接收绑定命令输出并实时推导二维码状态。
           */
          @Override
          public void onOutput(String chunk) {
            synchronized (output) {
              output.append(chunk);
              patchBinding(instance, inferWechatState(output.toString(), null));
            }
          }

          /**
           * 绑定命令结束后同步已配对账号并发布最新状态。
           */

          @Override
          public void onComplete(int exitCode) {
            try {
              boolean shouldSyncAccounts;
              boolean shouldRestartGateway;
              boolean successfulLogin;
              boolean gatewayFailure;
              synchronized (output) {
                String text = output.toString();
                WechatState state = inferWechatState(text, exitCode == 0 ? "connected" : "error");
                gatewayFailure = hasWechatGatewayFailure(text);
                successfulLogin = hasSuccessfulWechatLogin(text);
                if (gatewayFailure && !successfulLogin) {
                  state = state.withStatus("error");
                }
                patchBinding(instance, state);
                shouldSyncAccounts = successfulLogin || (exitCode == 0 && !gatewayFailure);
                shouldRestartGateway = successfulLogin;
              }
              if (shouldSyncAccounts) {
                accountSyncService.syncInstanceAccounts(instance);
              }
              if (successfulLogin) {
                log.info("微信扫码登录凭证已保存：instanceId={}, exitCode={}, gatewayFailure={}", instance.getId(), exitCode, gatewayFailure);
              } else if (exitCode != 0 || gatewayFailure) {
                log.warn("微信扫码绑定命令结束但未确认成功：instanceId={}, exitCode={}, gatewayFailure={}", instance.getId(), exitCode, gatewayFailure);
              }
              if (shouldRestartGateway) {
                restartGatewayAfterSuccessfulLogin(instance);
              }
              publishCurrent(instance.getId());
            } finally {
              jobs.remove(instance.getId());
            }
          }

          /**
           * 绑定命令超时时保存末尾输出，方便前端提示失败原因。
           */

          @Override
          public void onTimeout() {
            try {
              log.warn("微信扫码绑定任务超时：instanceId={}", instance.getId());
              synchronized (output) {
                patchBinding(instance, WechatState.error(tailSnippet(output + "\n微信绑定命令执行超时。", 3000)));
              }
              publishCurrent(instance.getId());
            } finally {
              jobs.remove(instance.getId());
            }
          }

          /**
           * 绑定命令异常时记录错误并结束当前任务。
           */

          @Override
          public void onError(Throwable error) {
            try {
              log.warn(
                  "微信扫码绑定任务异常：instanceId={}, reason={}",
                  instance.getId(),
                  error.getMessage() == null ? String.valueOf(error) : error.getMessage()
              );
              log.debug("微信扫码绑定任务异常详情：instanceId={}", instance.getId(), error);
              patchBinding(instance, WechatState.error(tailSnippet(error.getMessage() == null ? String.valueOf(error) : error.getMessage(), 3000)));
              publishCurrent(instance.getId());
            } finally {
              jobs.remove(instance.getId());
            }
          }
        }
    );
    jobs.put(instance.getId(), handle);
    publishCurrent(instance.getId());
  }

  private boolean isCurrentQrExpired(String instanceId) {
    InstanceWechatBindingEntity binding = aggregateMapper.listWechatBindingByInstanceIds(List.of(instanceId))
        .stream()
        .findFirst()
        .orElse(null);
    if (binding == null || !"waiting_scan".equals(binding.getStatus())) {
      return false;
    }
    String qrExpiresAt = binding.getQrExpiresAt();
    if (qrExpiresAt == null || qrExpiresAt.isBlank()) {
      return false;
    }
    try {
      return !Instant.parse(qrExpiresAt).isAfter(Instant.now());
    } catch (DateTimeParseException error) {
      return false;
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
      next.setQrPayload(state.qrPayload());
      next.setQrLink(state.qrLink());
      next.setQrExpiresAt(resolveQrExpiresAt(current, state, now));
    } else {
      next.setQrMode(null);
      next.setQrPayload("");
      next.setQrLink("");
      next.setQrExpiresAt(null);
    }
    next.setOutputSnippet(state.outputSnippet());
    boolean connected = "connected".equals(state.status());
    next.setRuntimeReady(false);
    next.setRuntimeStatus(connected ? "initializing" : "idle");
    next.setRuntimeMessage(connected ? RUNTIME_INIT_MESSAGE : "");
    next.setRuntimeUpdatedAt(connected ? now.toString() : null);
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

  private static boolean sameQr(InstanceWechatBindingEntity current, WechatState state) {
    return defaultString(current.getQrMode()).equals(defaultString(state.qrMode()))
        && defaultString(current.getQrPayload()).equals(defaultString(state.qrPayload()))
        && defaultString(current.getQrLink()).equals(defaultString(state.qrLink()));
  }

  private void publishCurrent(String instanceId) {
    queryService.findPublicInstance(instanceId, null).ifPresent(publicInstance -> {
      eventPublisher.publishWechatBindingUpdated(instanceId, publicInstance.wechatBinding());
      eventPublisher.publishInstanceUpdated(publicInstance);
    });
  }

  private void restartGatewayAfterSuccessfulLogin(InstanceEntity instance) {
    try {
      log.info("微信登录成功后触发 Gateway 重启：instanceId={}", instance.getId());
      patchRuntimeRestarting(instance);
      provisioningService.startProvisioning(instance.getId());
    } catch (RuntimeException error) {
      log.warn(
          "微信登录成功后触发 Gateway 重启失败：instanceId={}, reason={}",
          instance.getId(),
          error.getMessage() == null ? String.valueOf(error) : error.getMessage()
      );
      log.debug("微信登录成功后触发 Gateway 重启异常详情：instanceId={}", instance.getId(), error);
      patchBinding(instance, WechatState.error(tailSnippet(
          "微信登录凭证已保存，但重启 Gateway 失败：" + (error.getMessage() == null ? String.valueOf(error) : error.getMessage()),
          3000
      )));
    }
  }

  private void patchRuntimeRestarting(InstanceEntity instance) {
    InstanceWechatBindingEntity current = aggregateMapper.listWechatBindingByInstanceIds(List.of(instance.getId()))
        .stream()
        .findFirst()
        .orElse(null);
    Instant now = Instant.now();
    InstanceWechatBindingEntity next = new InstanceWechatBindingEntity();
    next.setInstanceId(instance.getId());
    next.setStatus("connected");
    next.setUpdatedAt(now.toString());
    next.setQrMode(null);
    next.setQrPayload("");
    next.setQrLink("");
    next.setQrExpiresAt(null);
    next.setOutputSnippet(current == null ? "" : defaultString(current.getOutputSnippet()));
    next.setRuntimeReady(false);
    next.setRuntimeStatus("restarting");
    next.setRuntimeMessage(RUNTIME_RESTARTING_MESSAGE);
    next.setRuntimeUpdatedAt(now.toString());
    mutationMapper.updateWechatBinding(next);
    publishCurrent(instance.getId());
  }

  private WechatState inferWechatState(String output, String fallbackStatus) {
    String status = fallbackStatus == null || fallbackStatus.isBlank() ? "starting" : fallbackStatus;
    String qrMode = null;
    String qrPayload = "";
    String qrLink = extractQrLink(output);

    Matcher dataUrl = DATA_URL.matcher(output);
    if (dataUrl.find()) {
      status = "waiting_scan";
      qrMode = "image";
      qrPayload = dataUrl.group();
    } else {
      String asciiQr = extractAsciiQr(output);
      if (!asciiQr.isBlank()) {
        status = "waiting_scan";
        qrMode = "ascii";
        qrPayload = asciiQr;
      }
    }

    if (Pattern.compile("已扫码|scaned|scanned", Pattern.CASE_INSENSITIVE).matcher(output).find()) {
      status = "scanned";
    }
    boolean successfulLogin = hasSuccessfulWechatLogin(output);
    if (successfulLogin) {
      status = "connected";
    }
    if (hasWechatGatewayFailure(output) && !successfulLogin) {
      status = "error";
    }
    return new WechatState(status, qrMode, qrPayload, qrLink, tailSnippet(output, 2000));
  }

  private static String buildWechatCommand() {
    return """
        set -e
        PLUGIN_DIR="/var/lib/openclaw/.openclaw/extensions/%s"
        if [ ! -d "$PLUGIN_DIR" ]; then
          echo "Runner 镜像内未找到预装微信插件：%s" >&2
          exit 1
        fi
        openclaw config set plugins.entries.%s.enabled true
        openclaw config set channels.%s.enabled true
        openclaw config set session.dmScope per-account-channel-peer
        openclaw channels login --channel %s --verbose
        """.formatted(WECHAT_CHANNEL_ID, WECHAT_PLUGIN_SPEC, WECHAT_CHANNEL_ID, WECHAT_CHANNEL_ID, WECHAT_CHANNEL_ID).trim();
  }

  private static boolean hasWechatGatewayFailure(String output) {
    return Pattern.compile(
            "gateway client error|running gateway did not restart|gateway closed|ECONNREFUSED|Could not start the CLI|Failed to open the plugin state database",
            Pattern.CASE_INSENSITIVE
        )
        .matcher(output == null ? "" : output)
        .find();
  }

  private static boolean hasSuccessfulWechatLogin(String output) {
    return Pattern.compile(
            "已将此\\s*OpenClaw\\s*连接到微信|连接成功|login confirmed|与微信连接成功|Local login saved auth",
            Pattern.CASE_INSENSITIVE
        )
        .matcher(output == null ? "" : output)
        .find();
  }

  private static String extractQrLink(String output) {
    Matcher labeled = Pattern.compile("(?:二维码链接|QR Code URL):\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(output);
    if (labeled.find()) {
      return labeled.group(1);
    }
    Matcher direct = DIRECT_LINK.matcher(output == null ? "" : output);
    return direct.find() ? direct.group() : "";
  }

  private static String extractAsciiQr(String output) {
    String[] lines = (output == null ? "" : output).split("\\R");
    List<String> best = new java.util.ArrayList<>();
    List<String> current = new java.util.ArrayList<>();
    for (String line : lines) {
      if (line.matches(".*[█▀▄▌▐▓▒░#].*") && line.trim().length() >= 10) {
        current.add(line);
      } else {
        if (current.size() > best.size()) {
          best = current;
        }
        current = new java.util.ArrayList<>();
      }
    }
    if (current.size() > best.size()) {
      best = current;
    }
    return best.size() < 4 ? "" : String.join("\n", best);
  }

  private static String tailSnippet(String value, int maxLength) {
    String text = value == null ? "" : value;
    if (text.length() <= maxLength) {
      return text;
    }
    return "…" + text.substring(text.length() - maxLength + 1);
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

    static WechatState error(String outputSnippet) {
      return new WechatState("error", null, "", "", outputSnippet);
    }

    WechatState withStatus(String status) {
      return new WechatState(status, qrMode, qrPayload, qrLink, outputSnippet);
    }

    boolean hasQrPayload() {
      return !defaultString(qrPayload).isBlank() || !defaultString(qrLink).isBlank();
    }
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
