package com.clawbotforall.runtime;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 基于 Docker 客户端实现的 OpenClaw 容器生命周期操作。
 */
@Service
public class DockerJavaOpenClawRuntime implements OpenClawRuntime {

  private static final int GATEWAY_PORT = 18789;
  private static final Logger log = LoggerFactory.getLogger(DockerJavaOpenClawRuntime.class);

  private final DockerClient dockerClient;
  private final ClawbotProperties properties;
  private final OpenVikingSettingsService openVikingSettingsService;
  private final ScheduledExecutorService execTimeouts = Executors.newScheduledThreadPool(2);
  private volatile InspectContainerResponse appContainer;

  @Autowired
  public DockerJavaOpenClawRuntime(
      ClawbotProperties properties,
      OpenVikingSettingsService openVikingSettingsService
  ) {
    this(createDockerClient(), properties, openVikingSettingsService);
  }

  DockerJavaOpenClawRuntime(
      DockerClient dockerClient,
      ClawbotProperties properties,
      OpenVikingSettingsService openVikingSettingsService
  ) {
    this.dockerClient = dockerClient;
    this.properties = properties;
    this.openVikingSettingsService = openVikingSettingsService;
  }

  private static DockerClient createDockerClient() {
    var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    var httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig())
        .maxConnections(100)
        .connectionTimeout(Duration.ofSeconds(30))
        .responseTimeout(Duration.ofSeconds(120))
        .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }

  /**
   * 创建或启动实例运行容器。
   */

  @Override
  public RuntimeState startInstance(InstanceEntity instance, InstancePaths paths) {
    log.info(
        "准备启动 OpenClaw 实例：instanceId={}, container={}, port={}, image={}",
        instance.getId(),
        instance.getContainerName(),
        instance.getPort(),
        properties.runtime().runnerImage()
    );
    ensureRunnerImage();
    stopInstance(instance);

    ExposedPort gatewayPort = ExposedPort.tcp(GATEWAY_PORT);
    Ports portBindings = new Ports();
    portBindings.bind(gatewayPort, Ports.Binding.bindPort(instance.getPort()));

    HostConfig hostConfig = HostConfig.newHostConfig()
        .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
        .withPortBindings(portBindings)
        .withBinds(
            new Bind(resolveHostBindPath(paths.homeDir()), new Volume("/var/lib/openclaw")),
            new Bind(resolveHostBindPath(paths.workspaceDir()), new Volume("/workspace"))
        );
    applyResourceLimits(hostConfig);

    var createCommand = dockerClient.createContainerCmd(properties.runtime().runnerImage())
        .withName(instance.getContainerName())
        .withExposedPorts(gatewayPort)
        .withEnv(runnerEnv(
            openVikingSettingsService.effectiveSettings(),
            instance.getId(),
            properties.runtime().runnerNodeMaxOldSpaceMb(),
            properties.oomDiagnostics()
        ))
        .withHostConfig(hostConfig);

    String sharedNetwork = resolveSharedDockerNetwork();
    if (!sharedNetwork.isBlank()) {
      createCommand.withNetworkMode(sharedNetwork)
          .withAliases(instance.getContainerName());
    }

    CreateContainerResponse container = createCommand.exec();

    dockerClient.startContainerCmd(container.getId()).exec();
    RuntimeState state = inspectInstance(instance);
    log.info(
        "OpenClaw 实例容器已启动：instanceId={}, container={}, dockerId={}, status={}",
        instance.getId(),
        instance.getContainerName(),
        container.getId(),
        state.status()
    );
    return state;
  }

  /**
   * 停止实例运行容器。
   */

  @Override
  public RuntimeState stopInstance(InstanceEntity instance) {
    try {
      dockerClient.removeContainerCmd(instance.getContainerName())
          .withForce(true)
          .exec();
      log.info("OpenClaw 实例容器已停止并移除：instanceId={}, container={}", instance.getId(), instance.getContainerName());
    } catch (NotFoundException ignored) {
      return RuntimeState.stopped();
    }
    return RuntimeState.stopped();
  }

  /**
   * 检查实例容器当前运行状态。
   */

  @Override
  public RuntimeState inspectInstance(InstanceEntity instance) {
    try {
      var inspect = dockerClient.inspectContainerCmd(instance.getContainerName()).exec();
      var state = inspect.getState();
      boolean running = Boolean.TRUE.equals(state.getRunning());
      String status = state.getStatus() == null || state.getStatus().isBlank()
          ? (running ? "running" : "stopped")
          : state.getStatus();
      return new RuntimeState(running, status, state.getStartedAt());
    } catch (NotFoundException ignored) {
      return RuntimeState.stopped();
    }
  }

  @Override
  public String getLogs(InstanceEntity instance, int tail) {
    int normalizedTail = Math.max(1, Math.min(5000, tail));
    StringBuilder output = new StringBuilder();
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    dockerClient.logContainerCmd(instance.getContainerName())
        .withStdOut(true)
        .withStdErr(true)
        .withTail(normalizedTail)
        .exec(new ResultCallback<Frame>() {
          @Override
          public void onStart(Closeable closeable) {
            // 无需处理。
          }

          @Override
          public void onNext(Frame frame) {
            output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
          }

          /**
           * 记录日志读取过程中的 Docker 回调错误。
           */

          @Override
          public void onError(Throwable throwable) {
            errorRef.set(throwable);
            completed.countDown();
          }

          /**
           * 日志流结束时唤醒等待线程。
           */

          @Override
          public void onComplete() {
            completed.countDown();
          }

          @Override
          public void close() {
            // 无需处理。
          }
        });

    await(completed, 30_000, "读取实例日志超时。");
    if (errorRef.get() != null) {
      if (errorRef.get() instanceof NotFoundException) {
        return "";
      }
      throw new IllegalStateException("读取实例日志失败。", errorRef.get());
    }
    return output.toString().trim();
  }

  @Override
  public InstanceStats getStats(InstanceEntity instance) {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<Statistics> statsRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    dockerClient.statsCmd(instance.getContainerName())
        .withNoStream(true)
        .exec(new ResultCallback<Statistics>() {
          @Override
          public void onStart(Closeable closeable) {
            // 无需处理。
          }

          @Override
          public void onNext(Statistics statistics) {
            statsRef.set(statistics);
            completed.countDown();
          }

          /**
           * 记录资源统计读取过程中的 Docker 回调错误。
           */

          @Override
          public void onError(Throwable throwable) {
            errorRef.set(throwable);
            completed.countDown();
          }

          /**
           * 统计流结束时唤醒等待线程。
           */

          @Override
          public void onComplete() {
            completed.countDown();
          }

          @Override
          public void close() {
            // 无需处理。
          }
        });

    await(completed, 5_000, "读取实例资源统计超时。");
    if (errorRef.get() != null || statsRef.get() == null) {
      return null;
    }
    return toInstanceStats(statsRef.get());
  }

  /**
   * 解析 Control UI 代理访问实例时使用的网络目标。
   */

  @Override
  public ProxyTarget resolveProxyTarget(InstanceEntity instance) {
    String sharedNetwork = resolveSharedDockerNetwork();
    if (!sharedNetwork.isBlank()) {
      return new ProxyTarget(instance.getContainerName(), GATEWAY_PORT, "container-network", sharedNetwork);
    }
    return new ProxyTarget("127.0.0.1", instance.getPort(), "published-port", "");
  }

  @Override
  public RunnerImageStatus getRunnerImageStatus() {
    String image = properties.runtime().runnerImage();
    try {
      var inspect = dockerClient.inspectImageCmd(image).exec();
      return new RunnerImageStatus(
          image,
          "ready",
          "本地已存在 runner 镜像。",
          true,
          inspect.getId(),
          Instant.now().toString()
      );
    } catch (NotFoundException ignored) {
      return new RunnerImageStatus(
          image,
          "missing",
          "本地尚未找到 runner 镜像。",
          false,
          "",
          Instant.now().toString()
      );
    } catch (Exception error) {
      return new RunnerImageStatus(
          image,
          "error",
          "检查 runner 镜像失败：" + (error.getMessage() == null ? String.valueOf(error) : error.getMessage()),
          false,
          "",
          Instant.now().toString()
      );
    }
  }

  /**
   * 从 Docker 刷新本地 OpenClaw 运行镜像状态。
   */

  @Override
  public RunnerImageStatus refreshRunnerImage() {
    String image = properties.runtime().runnerImage();
    try {
      log.info("开始刷新 OpenClaw runner 镜像：image={}", image);
      boolean completed = dockerClient.pullImageCmd(image)
          .start()
          .awaitCompletion(properties.runtime().runnerPullTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!completed) {
        log.warn("刷新 OpenClaw runner 镜像超时：image={}", image);
        return new RunnerImageStatus(
            image,
            "error",
            "拉取 OpenClaw runner 镜像超时（"
                + Math.round(properties.runtime().runnerPullTimeoutMs() / 60000.0)
                + " 分钟）。",
            false,
            "",
            Instant.now().toString()
        );
      }
      RunnerImageStatus status = getRunnerImageStatus();
      log.info("OpenClaw runner 镜像刷新完成：image={}, imageId={}", status.image(), status.imageId());
      return new RunnerImageStatus(
          status.image(),
          "ready",
          "runner 镜像已刷新。",
          status.present(),
          status.imageId(),
          Instant.now().toString()
      );
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      log.warn("刷新 OpenClaw runner 镜像被中断：image={}", image);
      return new RunnerImageStatus(
          image,
          "error",
          "拉取 OpenClaw runner 镜像被中断。",
          false,
          "",
          Instant.now().toString()
      );
    } catch (Exception error) {
      log.warn("刷新 OpenClaw runner 镜像失败：image={}, reason={}", image, error.getMessage());
      log.debug("刷新 OpenClaw runner 镜像异常详情：image={}", image, error);
      return new RunnerImageStatus(
          image,
          "error",
          "拉取 OpenClaw runner 镜像失败：" + (error.getMessage() == null ? String.valueOf(error) : error.getMessage()),
          false,
          "",
          Instant.now().toString()
      );
    }
  }

  /**
   * 在实例容器内启动命令，并通过监听器流式返回执行生命周期。
   */

  @Override
  public RuntimeExecHandle startExec(
      InstanceEntity instance,
      String command,
      long timeoutMs,
      Map<String, String> env,
      RuntimeExecListener listener
  ) {
    return startExecInternal(instance, List.of("/bin/sh", "-lc", command), timeoutMs, env, listener);
  }

  @Override
  public RuntimeExecHandle startExec(
      InstanceEntity instance,
      List<String> command,
      long timeoutMs,
      Map<String, String> env,
      RuntimeExecListener listener
  ) {
    if (command == null || command.isEmpty() || command.stream().anyMatch(item -> item == null || item.isBlank())) {
      throw new IllegalArgumentException("容器命令不能为空。");
    }
    return startExecInternal(instance, command, timeoutMs, env, listener);
  }

  private RuntimeExecHandle startExecInternal(
      InstanceEntity instance,
      List<String> command,
      long timeoutMs,
      Map<String, String> env,
      RuntimeExecListener listener
  ) {
    var createCmd = dockerClient.execCreateCmd(instance.getContainerName())
        .withAttachStdout(true)
        .withAttachStderr(true)
        .withAttachStdin(true)
        .withCmd(command.toArray(String[]::new))
        .withTty(false);
    if (env != null && !env.isEmpty()) {
      createCmd.withEnv(env.entrySet().stream()
          .map(entry -> entry.getKey() + "=" + entry.getValue())
          .toList());
    }
    var exec = createCmd.exec();

    AtomicBoolean finished = new AtomicBoolean(false);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    AtomicReference<Closeable> streamRef = new AtomicReference<>();
    PipedOutputStream stdinWriter = new PipedOutputStream();
    PipedInputStream stdinReader;
    try {
      stdinReader = new PipedInputStream(stdinWriter);
    } catch (IOException error) {
      throw new IllegalStateException("创建容器命令 stdin 管道失败。", error);
    }
    ScheduledFuture<?> timeout = execTimeouts.schedule(() -> {
      if (finished.compareAndSet(false, true)) {
        cancelled.set(true);
        closeQuietly(streamRef.get());
        closeQuietly(stdinWriter);
        closeQuietly(stdinReader);
        listener.onTimeout();
      }
    }, Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);

    dockerClient.execStartCmd(exec.getId())
        .withDetach(false)
        .withTty(false)
        .withStdIn(stdinReader)
        .exec(new ResultCallback<Frame>() {
          @Override
          public void onStart(Closeable closeable) {
            streamRef.set(closeable);
          }

          @Override
          public void onNext(Frame frame) {
            if (!finished.get()) {
              listener.onOutput(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
          }

          /**
           * 将容器命令执行错误转交给业务监听器。
           */

          @Override
          public void onError(Throwable throwable) {
            if (finished.compareAndSet(false, true)) {
              timeout.cancel(false);
              closeQuietly(stdinWriter);
              closeQuietly(stdinReader);
              listener.onError(throwable);
            }
          }

          /**
           * 容器命令结束后读取退出码并通知业务监听器。
           */

          @Override
          public void onComplete() {
            if (finished.compareAndSet(false, true)) {
              timeout.cancel(false);
              closeQuietly(stdinWriter);
              closeQuietly(stdinReader);
              listener.onComplete(inspectExecExitCode(exec.getId()));
            }
          }

          @Override
          public void close() {
            closeQuietly(streamRef.get());
            closeQuietly(stdinWriter);
            closeQuietly(stdinReader);
          }
        });

    return new RuntimeExecHandle() {
      /**
       * 向待处理的交互式认证命令发送文本。
       */
      @Override
      public void sendInput(String input) {
        if (finished.get()) {
          return;
        }
        try {
          stdinWriter.write((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
          stdinWriter.flush();
        } catch (IOException ignored) {
          // 远端命令可能已经关闭标准输入。
        }
      }

      /**
       * 取消正在运行的运行时命令。
       */

      @Override
      public void cancel() {
        if (finished.compareAndSet(false, true)) {
          cancelled.set(true);
          timeout.cancel(false);
          closeQuietly(streamRef.get());
          closeQuietly(stdinWriter);
          closeQuietly(stdinReader);
        }
      }

      @Override
      public boolean isCancelled() {
        return cancelled.get();
      }
    };
  }

  private void ensureRunnerImage() {
    String image = properties.runtime().runnerImage();
    try {
      dockerClient.inspectImageCmd(image).exec();
      log.info("OpenClaw runner 镜像本地已存在，跳过拉取：image={}", image);
      return;
    } catch (NotFoundException ignored) {
      // 下方开始拉取数据。
    }

    try {
      log.info("本地未找到 OpenClaw runner 镜像，开始拉取：image={}", image);
      boolean completed = dockerClient.pullImageCmd(image)
          .start()
          .awaitCompletion(properties.runtime().runnerPullTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!completed) {
        log.warn("拉取 OpenClaw runner 镜像超时：image={}", image);
        throw new IllegalStateException(
            "拉取 OpenClaw runner 镜像超时（"
                + Math.round(properties.runtime().runnerPullTimeoutMs() / 60000.0)
                + " 分钟）：" + image
        );
      }
      log.info("OpenClaw runner 镜像拉取完成：image={}", image);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      log.warn("拉取 OpenClaw runner 镜像被中断：image={}", image);
      throw new IllegalStateException("拉取 OpenClaw runner 镜像被中断：" + image, error);
    }
  }

  static List<String> runnerEnv(OpenVikingEffectiveSettings settings, String instanceId) {
    return runnerEnv(settings, instanceId, 0);
  }

  static List<String> runnerEnv(OpenVikingEffectiveSettings settings, String instanceId, int nodeMaxOldSpaceMb) {
    return runnerEnv(settings, instanceId, nodeMaxOldSpaceMb, ClawbotProperties.OomDiagnostics.defaults());
  }

  static List<String> runnerEnv(
      OpenVikingEffectiveSettings settings,
      String instanceId,
      int nodeMaxOldSpaceMb,
      ClawbotProperties.OomDiagnostics diagnostics
  ) {
    List<String> env = new ArrayList<>(List.of(
        "HOME=/var/lib/openclaw",
        "OPENCLAW_HOME=/var/lib/openclaw",
        "OPENCLAW_CONFIG_PATH=/var/lib/openclaw/openclaw.json",
        "OPENCLAW_CONFIG=/var/lib/openclaw/openclaw.json",
        "OPENCLAW_STATE_DIR=/var/lib/openclaw/.openclaw",
        "OPENVIKING_TRUSTED_MODE_ENABLED=" + settings.trustedModeEnabled(),
        "OPENVIKING_ACCOUNT_ID=" + settings.accountId(),
        "OPENVIKING_IDENTITY_HASH_SECRET=" + settings.identityHashSecret(),
        "CLAW_MANAGER_INTERNAL_BASE_URL=" + settings.internalBaseUrl(),
        "OPENVIKING_BROKER_TOKEN=" + settings.brokerToken(),
        "OPENVIKING_OPENCLAW_INSTANCE_ID=" + instanceId
    ));
    List<String> nodeOptions = new ArrayList<>();
    if (nodeMaxOldSpaceMb > 0) {
      nodeOptions.add("--max-old-space-size=" + nodeMaxOldSpaceMb);
    }
    if (diagnostics != null && diagnostics.enabled()) {
      env.add("CLAW_MANAGER_OOM_DIAGNOSTICS_ENABLED=true");
      env.add("CLAW_MANAGER_OOM_DIAGNOSTICS_DIR=/var/lib/openclaw/diagnostics/oom");
      if (diagnostics.snapshotEnabledFor(instanceId)) {
        nodeOptions.add("--heapsnapshot-signal=SIGUSR2");
        nodeOptions.add("--diagnostic-dir=/var/lib/openclaw/diagnostics/oom/snapshots");
      }
    }
    if (!nodeOptions.isEmpty()) {
      env.add("NODE_OPTIONS=" + String.join(" ", nodeOptions));
    }
    if (hasText(settings.baseUrl())) {
      env.add("OPENVIKING_BASE_URL=" + settings.baseUrl());
    }
    if (hasText(settings.pluginPackage())) {
      env.add("OPENVIKING_PLUGIN_PACKAGE=" + settings.pluginPackage());
    }
    return env;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private int inspectExecExitCode(String execId) {
    try {
      Integer exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCode();
      return exitCode == null ? -1 : exitCode;
    } catch (Exception ignored) {
      return -1;
    }
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
      // 尽力处理。
    }
  }

  private String resolveSharedDockerNetwork() {
    InspectContainerResponse current = inspectAppContainer().orElse(null);
    if (current == null || current.getNetworkSettings() == null || current.getNetworkSettings().getNetworks() == null) {
      return "";
    }
    Map<String, ContainerNetwork> networks = current.getNetworkSettings().getNetworks();
    return networks.keySet().stream()
        .filter(name -> name != null && !name.isBlank() && !"bridge".equals(name) && !"host".equals(name) && !"none".equals(name))
        .findFirst()
        .orElse("");
  }

  private String resolveHostBindPath(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    InspectContainerResponse current = inspectAppContainer().orElse(null);
    if (current == null || current.getMounts() == null) {
      return absolute.toString();
    }

    return current.getMounts().stream()
        .filter(mount -> mount.getDestination() != null && mount.getDestination().getPath() != null)
        .map(mount -> new MountMapping(
            Path.of(mount.getDestination().getPath()).normalize(),
            Path.of(mount.getSource()).normalize()
        ))
        .filter(mapping -> absolute.startsWith(mapping.destination()))
        .max(Comparator.comparingInt(mapping -> mapping.destination().getNameCount()))
        .map(mapping -> {
          Path relative = mapping.destination().relativize(absolute);
          return mapping.source().resolve(relative).normalize().toString();
        })
        .orElse(absolute.toString());
  }

  private Optional<InspectContainerResponse> inspectAppContainer() {
    if (appContainer != null) {
      return Optional.of(appContainer);
    }

    String containerId = currentContainerId();
    if (containerId.isBlank()) {
      return Optional.empty();
    }

    try {
      appContainer = dockerClient.inspectContainerCmd(containerId).exec();
      return Optional.of(appContainer);
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private String currentContainerId() {
    String hostname = System.getenv("HOSTNAME");
    if (hostname != null && !hostname.isBlank()) {
      return hostname.trim();
    }
    Path cgroup = Path.of("/proc/self/cgroup");
    if (Files.exists(cgroup)) {
      try {
        return Files.readAllLines(cgroup).stream()
            .map(line -> line.substring(line.lastIndexOf('/') + 1))
            .filter(value -> value.length() >= 12)
            .findFirst()
            .orElse("");
      } catch (Exception ignored) {
        return "";
      }
    }
    return "";
  }

  private InstanceStats toInstanceStats(Statistics stats) {
    long memoryUsage = stats.getMemoryStats() == null || stats.getMemoryStats().getUsage() == null
        ? 0L
        : stats.getMemoryStats().getUsage();
    long memoryLimit = stats.getMemoryStats() == null || stats.getMemoryStats().getLimit() == null
        ? 0L
        : stats.getMemoryStats().getLimit();
    long rxBytes = 0L;
    long txBytes = 0L;
    if (stats.getNetworks() != null) {
      for (StatisticNetworksConfig network : stats.getNetworks().values()) {
        rxBytes += network.getRxBytes() == null ? 0L : network.getRxBytes();
        txBytes += network.getTxBytes() == null ? 0L : network.getTxBytes();
      }
    }
    long pids = stats.getPidsStats() == null || stats.getPidsStats().getCurrent() == null
        ? 0L
        : stats.getPidsStats().getCurrent();
    return new InstanceStats(
        formatPercent(cpuPercent(stats)),
        formatBytes(memoryUsage) + " / " + formatBytes(memoryLimit),
        memoryLimit <= 0 ? "0.00%" : formatPercent((double) memoryUsage / memoryLimit * 100.0),
        formatBytes(rxBytes) + " / " + formatBytes(txBytes),
        String.valueOf(pids)
    );
  }

  private static double cpuPercent(Statistics stats) {
    if (stats.getCpuStats() == null || stats.getPreCpuStats() == null) {
      return 0.0;
    }
    Long total = stats.getCpuStats().getCpuUsage() == null ? null : stats.getCpuStats().getCpuUsage().getTotalUsage();
    Long previousTotal = stats.getPreCpuStats().getCpuUsage() == null ? null : stats.getPreCpuStats().getCpuUsage().getTotalUsage();
    Long system = stats.getCpuStats().getSystemCpuUsage();
    Long previousSystem = stats.getPreCpuStats().getSystemCpuUsage();
    if (total == null || previousTotal == null || system == null || previousSystem == null) {
      return 0.0;
    }
    long cpuDelta = total - previousTotal;
    long systemDelta = system - previousSystem;
    if (cpuDelta <= 0 || systemDelta <= 0) {
      return 0.0;
    }
    long onlineCpus = stats.getCpuStats().getOnlineCpus() == null ? 1L : stats.getCpuStats().getOnlineCpus();
    return ((double) cpuDelta / systemDelta) * onlineCpus * 100.0;
  }

  private static String formatPercent(double value) {
    return String.format(java.util.Locale.ROOT, "%.2f%%", value);
  }

  private static String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + "B";
    }
    double value = bytes;
    String[] units = {"B", "KB", "MB", "GB", "TB"};
    int unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
      value /= 1024;
      unitIndex += 1;
    }
    return String.format(java.util.Locale.ROOT, "%.2f%s", value, units[unitIndex]);
  }

  private static void await(CountDownLatch latch, long timeoutMs, String timeoutMessage) {
    try {
      boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
      if (!completed) {
        throw new IllegalStateException(timeoutMessage);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(timeoutMessage, error);
    }
  }

  private record MountMapping(Path destination, Path source) {}

  private void applyResourceLimits(HostConfig hostConfig) {
    Long nanoCpus = parseCpus(properties.runtime().runnerCpus());
    if (nanoCpus != null) {
      hostConfig.withNanoCPUs(nanoCpus);
    }

    Long memoryBytes = parseMemoryBytes(properties.runtime().runnerMemory());
    if (memoryBytes != null) {
      hostConfig.withMemory(memoryBytes);
    }
    Long memorySwapBytes = parseMemoryBytes(properties.runtime().runnerMemorySwap());
    if (memorySwapBytes != null) {
      hostConfig.withMemorySwap(memorySwapBytes);
    }
  }

  private static Long parseCpus(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) {
      return null;
    }
    BigDecimal cpus = new BigDecimal(normalized);
    return cpus.multiply(BigDecimal.valueOf(1_000_000_000L)).longValue();
  }

  private static Long parseMemoryBytes(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase();
    if (normalized.isBlank()) {
      return null;
    }

    long multiplier = 1;
    String number = normalized;
    List<String> suffixes = new ArrayList<>(List.of("gib", "gb", "g", "mib", "mb", "m", "kib", "kb", "k"));
    for (String suffix : suffixes) {
      if (normalized.endsWith(suffix)) {
        number = normalized.substring(0, normalized.length() - suffix.length()).trim();
        multiplier = switch (suffix.charAt(0)) {
          case 'g' -> 1024L * 1024L * 1024L;
          case 'm' -> 1024L * 1024L;
          case 'k' -> 1024L;
          default -> 1L;
        };
        break;
      }
    }
    return new BigDecimal(number).multiply(BigDecimal.valueOf(multiplier)).longValue();
  }
}
