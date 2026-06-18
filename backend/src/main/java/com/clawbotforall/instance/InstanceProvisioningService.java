package com.clawbotforall.instance;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.ProxyTarget;
import com.clawbotforall.runtime.RuntimeState;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * 启动并监控 OpenClaw 实例创建流程，直到 Gateway 就绪。
 */
@Service
public class InstanceProvisioningService {

  private final InstanceCommandService commandService;
  private final InstanceFileService fileService;
  private final OpenClawRuntime openClawRuntime;
  private final InstanceQueryService queryService;
  private final InstanceEventPublisher eventPublisher;
  private final ClawbotProperties properties;
  private final HttpClient httpClient;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, Boolean> jobs = new ConcurrentHashMap<>();

  public InstanceProvisioningService(
      InstanceCommandService commandService,
      InstanceFileService fileService,
      OpenClawRuntime openClawRuntime,
      InstanceQueryService queryService,
      InstanceEventPublisher eventPublisher,
      ClawbotProperties properties
  ) {
    this.commandService = commandService;
    this.fileService = fileService;
    this.openClawRuntime = openClawRuntime;
    this.queryService = queryService;
    this.eventPublisher = eventPublisher;
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.runtime().gatewayReadyProbeTimeoutMs()))
        .build();
  }

  /**
   * 在没有进行中任务时启动实例异步创建检查流程。
   */

  public void startProvisioning(String instanceId) {
    if (jobs.putIfAbsent(instanceId, true) != null) {
      return;
    }
    executor.submit(() -> {
      try {
        runProvisioning(instanceId);
      } finally {
        jobs.remove(instanceId);
      }
    });
  }

  private void runProvisioning(String instanceId) {
    try {
      ProvisioningUpdate writingConfig = update(
          instanceId,
          null,
          "running",
          18,
          "writing-config",
          "正在写入实例目录、令牌和 OpenClaw 配置。",
          null
      );

      List<InstanceModelEntity> models = commandService.listModels(instanceId);
      InstancePaths paths = fileService.writeInstanceFiles(writingConfig.instance(), models);

      update(
          instanceId,
          null,
          "running",
          56,
          "starting-container",
          "正在拉起专属 OpenClaw 容器。",
          null
      );

      RuntimeState runtimeState = openClawRuntime.startInstance(writingConfig.instance(), paths);
      String runtimeStatus = runtimeState.status() == null || runtimeState.status().isBlank()
          ? "running"
          : runtimeState.status();
      String gatewayStartedAt = Instant.now().toString();
      update(
          instanceId,
          runtimeStatus,
          "running",
          0,
          "gateway-starting",
          gatewayProgressMessage(0),
          gatewayStartedAt
      );

      waitForGatewayReady(instanceId, gatewayStartedAt);
    } catch (Exception error) {
      update(
          instanceId,
          "stopped",
          "error",
          100,
          "error",
          trim(error.getMessage() == null ? String.valueOf(error) : error.getMessage(), 240),
          null
      );
    }
  }

  private void waitForGatewayReady(String instanceId, String gatewayStartedAt) throws InterruptedException {
    long deadline = System.currentTimeMillis() + properties.runtime().gatewayReadyTimeoutMs();
    int progress = 0;

    while (System.currentTimeMillis() < deadline) {
      InstanceEntity instance = commandService.requireInstance(instanceId);
      if (isGatewayReady(instance)) {
        update(
            instanceId,
            "running",
            "ready",
            100,
            "ready",
            "Gateway 已就绪，可以访问 Control UI 或绑定微信。",
            gatewayStartedAt
        );
        commandService.markWechatRuntimeReadyIfPaired(instanceId);
        queryService.findPublicInstance(instanceId, null)
            .ifPresent(eventPublisher::publishInstanceUpdated);
        return;
      }

      RuntimeState runtimeState = openClawRuntime.inspectInstance(instance);
      if (!runtimeState.running()) {
        throw new IllegalStateException("OpenClaw 容器已停止，Gateway 无法完成启动。");
      }

      long remainingMs = deadline - System.currentTimeMillis();
      Thread.sleep(Math.min(properties.runtime().gatewayReadyCheckIntervalMs(), Math.max(0, remainingMs)));
      progress = Math.min(95, progress + ThreadLocalRandom.current().nextInt(1, 6));
      update(
          instanceId,
          runtimeState.status(),
          "running",
          progress,
          "gateway-starting",
          gatewayProgressMessage(progress),
          gatewayStartedAt
      );
    }

    throw new IllegalStateException(
        "OpenClaw Gateway 启动超时（"
            + Math.round(properties.runtime().gatewayReadyTimeoutMs() / 60000.0)
            + " 分钟）。请稍后重试，或查看实例日志确认 OpenClaw 是否正常启动。"
    );
  }

  private ProvisioningUpdate update(
      String instanceId,
      String runtimeStatus,
      String status,
      int percent,
      String stage,
      String message,
      String gatewayStartedAt
  ) {
    ProvisioningUpdate update = commandService.updateProvisioning(
        instanceId,
        runtimeStatus,
        status,
        percent,
        stage,
        message,
        gatewayStartedAt
    );
    eventPublisher.publishProvisioningUpdated(instanceId, update.provisioning());
    return update;
  }

  private boolean isGatewayReady(InstanceEntity instance) {
    ProxyTarget target = openClawRuntime.resolveProxyTarget(instance);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(gatewayReadyUri(target))
        .timeout(Duration.ofMillis(properties.runtime().gatewayReadyProbeTimeoutMs()))
        .header("Authorization", "Bearer " + instance.getGatewayToken())
        .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
        .header("User-Agent", "clawbot-gateway-healthcheck")
        .GET()
        .build();
    try {
      HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() >= 100 && response.statusCode() < 500;
    } catch (IOException | InterruptedException error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  static URI gatewayReadyUri(ProxyTarget target) {
    return URI.create("http://" + target.host() + ":" + target.port() + "/");
  }

  private static String gatewayProgressMessage(int percent) {
    return "Gateway 启动中（" + percent + "%）。首次启动可能需要 5-30 分钟，请稍候。";
  }

  private static String trim(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value == null ? "" : value;
    }
    return value.substring(0, maxLength);
  }
}
