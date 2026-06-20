package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 按 1Panel 的 OpenClaw 插件安装方式管理微信插件。
 */
@Service
public class WechatPluginService {

  private static final String WECHAT_PLUGIN_TYPE = "weixin";
  private static final String WECHAT_PLUGIN_ID = "openclaw-weixin";
  private static final String WECHAT_PLUGIN_SPEC = "@tencent-weixin/openclaw-weixin";
  private static final String WECHAT_PLUGIN_NPM_SPEC = "npm:" + WECHAT_PLUGIN_SPEC;
  private static final long TASK_TIMEOUT_MS = 10 * 60 * 1000L;
  private static final long CHECK_TIMEOUT_MS = 20_000L;

  private final OpenClawRuntime openClawRuntime;
  private final InstanceCommandService commandService;
  private final InstanceFileService fileService;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final Executor executor;
  private final ConcurrentMap<String, PublicWechatPluginStatus> taskStatuses = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> taskJobs = new ConcurrentHashMap<>();

  @Autowired
  public WechatPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper
  ) {
    this(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        objectMapper,
        defaultExecutor()
    );
  }

  WechatPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Executor executor
  ) {
    this.openClawRuntime = openClawRuntime;
    this.commandService = commandService;
    this.fileService = fileService;
    this.mutationMapper = mutationMapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.executor = executor;
  }

  /**
   * 检查实例是否已经安装微信插件。
   */
  public PublicWechatPluginStatus status(InstanceEntity instance, boolean checkLatest) {
    PublicWechatPluginStatus running = taskStatuses.get(instance.getId());
    if (running != null && isRunningTask(running.status())) {
      return running;
    }
    return detectedStatus(instance, checkLatest);
  }

  private PublicWechatPluginStatus detectedStatus(InstanceEntity instance, boolean checkLatest) {
    String currentVersion = currentVersion(instance);
    boolean installed = !currentVersion.isBlank();
    String latestVersion = "";
    if (installed && checkLatest) {
      try {
        ExecResult latest = run(instance, List.of("npm", "view", WECHAT_PLUGIN_SPEC, "version", "--json"), CHECK_TIMEOUT_MS);
        latestVersion = latest.output().trim().replace("\"", "");
      } catch (RuntimeException ignored) {
        latestVersion = "";
      }
    }
    return new PublicWechatPluginStatus(
        installed,
        currentVersion,
        latestVersion,
        !currentVersion.isBlank() && !latestVersion.isBlank() && !currentVersion.equals(latestVersion),
        installed ? "installed" : "missing",
        installed ? "微信插件已安装。" : "微信插件尚未安装。",
        "",
        Instant.now().toString()
    );
  }

  public boolean isWechatPluginInstalled(InstanceEntity instance) {
    return !currentVersion(instance).isBlank();
  }

  /**
   * 异步安装或覆盖安装微信插件。
   */
  public PublicWechatPluginStatus startInstall(InstanceEntity instance) {
    return startTask(
        instance,
        "installing",
        "微信插件安装已开始。",
        "微信插件正在安装。",
        "微信插件安装失败：",
        List.of("openclaw", "plugins", "install", WECHAT_PLUGIN_NPM_SPEC, "--force"),
        output -> {
          enableWechatPlugin(instance);
          PublicWechatPluginStatus status = detectedStatus(instance, false);
          return new PublicWechatPluginStatus(
              status.installed(),
              status.currentVersion(),
              status.latestVersion(),
              status.upgradable(),
              status.installed() ? "installed" : "unknown",
              status.installed() ? "微信插件安装完成。" : "微信插件命令已完成，但未检测到插件目录。",
              output,
              Instant.now().toString()
          );
        }
    );
  }

  public PublicWechatPluginStatus startUninstall(InstanceEntity instance) {
    PublicWechatPluginStatus running = runningTask(instance, "uninstalling", "微信插件正在卸载。");
    if (running != null) {
      return running;
    }
    if (!isWechatPluginInstalled(instance)) {
      PublicWechatPluginStatus missing = missingStatus("微信插件尚未安装，无需卸载。");
      taskStatuses.put(instance.getId(), missing);
      publish(instance, missing);
      return missing;
    }
    return startTask(
        instance,
        "uninstalling",
        "微信插件卸载已开始。",
        "微信插件正在卸载。",
        "微信插件卸载失败：",
        List.of("openclaw", "plugins", "uninstall", WECHAT_PLUGIN_ID, "--force"),
        output -> {
          disableWechatPlugin(instance);
          return new PublicWechatPluginStatus(
              false,
              "",
              "",
              false,
              "missing",
              "微信插件已卸载。",
              output,
              Instant.now().toString()
          );
        }
    );
  }

  public PublicWechatPluginStatus startUpgrade(InstanceEntity instance) {
    PublicWechatPluginStatus running = runningTask(instance, "upgrading", "微信插件正在升级。");
    if (running != null) {
      return running;
    }
    if (!isWechatPluginInstalled(instance)) {
      PublicWechatPluginStatus missing = missingStatus("微信插件尚未安装，无法升级。");
      taskStatuses.put(instance.getId(), missing);
      publish(instance, missing);
      return missing;
    }
    return startTask(
        instance,
        "upgrading",
        "微信插件升级已开始。",
        "微信插件正在升级。",
        "微信插件升级失败：",
        List.of("openclaw", "plugins", "update", WECHAT_PLUGIN_ID),
        output -> {
          enableWechatPlugin(instance);
          PublicWechatPluginStatus status = detectedStatus(instance, false);
          return new PublicWechatPluginStatus(
              status.installed(),
              status.currentVersion(),
              status.latestVersion(),
              status.upgradable(),
              status.installed() ? "installed" : "missing",
              status.installed() ? "微信插件升级完成。" : "微信插件升级命令已完成，但未检测到插件目录。",
              output,
              Instant.now().toString()
          );
        }
    );
  }

  private PublicWechatPluginStatus runningTask(InstanceEntity instance, String fallbackStatus, String fallbackMessage) {
    requireRunning(instance);
    if (!taskJobs.containsKey(instance.getId())) {
      return null;
    }
    PublicWechatPluginStatus existing = taskStatuses.get(instance.getId());
    return existing == null ? runningStatus(fallbackStatus, "", fallbackMessage) : existing;
  }

  private PublicWechatPluginStatus startTask(
      InstanceEntity instance,
      String runningStatus,
      String startedMessage,
      String progressMessage,
      String failurePrefix,
      List<String> command,
      PluginTaskSuccess success
  ) {
    requireRunning(instance);
    String instanceId = instance.getId();
    PublicWechatPluginStatus existing = taskStatuses.get(instanceId);
    if (taskJobs.putIfAbsent(instanceId, true) != null) {
      return existing == null ? runningStatus(runningStatus, "", progressMessage) : existing;
    }
    PublicWechatPluginStatus started = runningStatus(runningStatus, "", startedMessage);
    taskStatuses.put(instanceId, started);
    publish(instance, started);
    try {
      executor.execute(() -> runTask(instance, runningStatus, progressMessage, failurePrefix, command, success));
    } catch (RuntimeException error) {
      taskJobs.remove(instanceId);
      PublicWechatPluginStatus failed = failedStatus(failurePrefix, message(error), "");
      taskStatuses.put(instanceId, failed);
      publish(instance, failed);
      throw error;
    }
    return started;
  }

  private void runTask(
      InstanceEntity instance,
      String runningStatus,
      String progressMessage,
      String failurePrefix,
      List<String> command,
      PluginTaskSuccess success
  ) {
    String instanceId = instance.getId();
    StringBuilder output = new StringBuilder();
    try {
      run(instance, command, TASK_TIMEOUT_MS, chunk -> {
        append(output, chunk);
        PublicWechatPluginStatus running = runningStatus(runningStatus, tail(output.toString(), 4000), progressMessage);
        taskStatuses.put(instanceId, running);
        publish(instance, running);
      });
      PublicWechatPluginStatus completed = success.complete(tail(output.toString(), 4000));
      taskStatuses.put(instanceId, completed);
      publish(instance, completed);
    } catch (RuntimeException error) {
      PublicWechatPluginStatus failed = failedStatus(failurePrefix, message(error), tail(output.toString(), 4000));
      taskStatuses.put(instanceId, failed);
      publish(instance, failed);
    } finally {
      taskJobs.remove(instanceId);
    }
  }

  private void enableWechatPlugin(InstanceEntity instance) {
    List<Object> allow = readJsonList(instance.getPluginsAllow());
    if (!allow.contains(WECHAT_PLUGIN_ID)) {
      allow = new ArrayList<>(allow);
      allow.add(WECHAT_PLUGIN_ID);
    }
    Map<String, Object> entries = readJsonMap(instance.getPluginsEntries());
    entries.put(WECHAT_PLUGIN_ID, Map.of("enabled", true));
    String now = Instant.now().toString();
    String allowJson = writeJson(allow);
    String entriesJson = writeJson(entries);
    mutationMapper.updateInstancePlugins(instance.getId(), allowJson, entriesJson, now);
    instance.setPluginsAllow(allowJson);
    instance.setPluginsEntries(entriesJson);
    instance.setUpdatedAt(now);
    fileService.writeInstanceFiles(instance, commandService.listModels(instance.getId()));
  }

  private void disableWechatPlugin(InstanceEntity instance) {
    List<Object> allow = new ArrayList<>(readJsonList(instance.getPluginsAllow()));
    allow.removeIf(item -> WECHAT_PLUGIN_ID.equals(String.valueOf(item)));
    Map<String, Object> entries = new LinkedHashMap<>(readJsonMap(instance.getPluginsEntries()));
    entries.remove(WECHAT_PLUGIN_ID);
    String now = Instant.now().toString();
    String allowJson = writeJson(allow);
    String entriesJson = writeJson(entries);
    mutationMapper.updateInstancePlugins(instance.getId(), allowJson, entriesJson, now);
    instance.setPluginsAllow(allowJson);
    instance.setPluginsEntries(entriesJson);
    instance.setUpdatedAt(now);
    fileService.writeInstanceFiles(instance, commandService.listModels(instance.getId()));
  }

  private String currentVersion(InstanceEntity instance) {
    InstancePaths paths = fileService.paths(instance.getId());
    Path packagePath = legacyPluginPackagePath(paths);
    if (Files.exists(packagePath)) {
      return readPluginVersion(packagePath);
    }
    return npmProjectPluginVersion(paths);
  }

  private String readPluginVersion(Path packagePath) {
    try {
      JsonNode json = objectMapper.readTree(packagePath.toFile());
      if (WECHAT_PLUGIN_SPEC.equals(json.path("name").asText("")) || WECHAT_PLUGIN_ID.equals(json.path("name").asText(""))) {
        return json.path("version").asText("");
      }
      return json.path("version").asText("");
    } catch (IOException error) {
      return "";
    }
  }

  private String npmProjectPluginVersion(InstancePaths paths) {
    Path projectsDir = paths.homeDir()
        .resolve(".openclaw")
        .resolve("npm")
        .resolve("projects");
    if (!Files.isDirectory(projectsDir)) {
      return "";
    }
    try (Stream<Path> packages = Files.find(
        projectsDir,
        3,
        (path, attrs) -> attrs.isRegularFile() && "package.json".equals(path.getFileName().toString())
    )) {
      return packages
          .map(this::readWechatNpmProjectVersion)
          .filter(version -> !version.isBlank())
          .findFirst()
          .orElse("");
    } catch (IOException error) {
      return "";
    }
  }

  private String readWechatNpmProjectVersion(Path packagePath) {
    try {
      JsonNode json = objectMapper.readTree(packagePath.toFile());
      if (WECHAT_PLUGIN_SPEC.equals(json.path("name").asText(""))) {
        return json.path("version").asText("");
      }
      return normalizeNpmVersion(json.path("dependencies").path(WECHAT_PLUGIN_SPEC).asText(""));
    } catch (IOException error) {
      return "";
    }
  }

  private String normalizeNpmVersion(String version) {
    String normalized = version == null ? "" : version.trim();
    if (normalized.startsWith("npm:")) {
      normalized = normalized.substring("npm:".length());
      int atIndex = normalized.lastIndexOf('@');
      if (atIndex > 0 && atIndex + 1 < normalized.length()) {
        normalized = normalized.substring(atIndex + 1);
      }
    }
    return normalized;
  }

  private Path legacyPluginPackagePath(InstancePaths paths) {
    return paths.homeDir()
        .resolve(".openclaw")
        .resolve("extensions")
        .resolve(WECHAT_PLUGIN_ID)
        .resolve("package.json");
  }

  private void requireRunning(InstanceEntity instance) {
    RuntimeState state = openClawRuntime.inspectInstance(instance);
    if (!state.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "请先启动该 OpenClaw 实例，再安装微信插件。");
    }
  }

  private ExecResult run(InstanceEntity instance, List<String> command, long timeoutMs) {
    return run(instance, command, timeoutMs, chunk -> {});
  }

  private ExecResult run(
      InstanceEntity instance,
      List<String> command,
      long timeoutMs,
      Consumer<String> onOutput
  ) {
    CountDownLatch done = new CountDownLatch(1);
    StringBuilder output = new StringBuilder();
    AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    AtomicReference<RuntimeExecHandle> handleRef = new AtomicReference<>();
    RuntimeExecHandle handle = openClawRuntime.startExec(instance, command, timeoutMs, Map.of(), new RuntimeExecListener() {
      @Override
      public void onOutput(String chunk) {
        String safeChunk = chunk == null ? "" : chunk;
        output.append(safeChunk);
        onOutput.accept(safeChunk);
      }

      @Override
      public void onComplete(int code) {
        exitCode.set(code);
        done.countDown();
      }

      @Override
      public void onTimeout() {
        errorRef.set(new IllegalStateException("命令执行超时：" + String.join(" ", command)));
        done.countDown();
      }

      @Override
      public void onError(Throwable error) {
        errorRef.set(error);
        done.countDown();
      }
    });
    handleRef.set(handle);
    try {
      if (!done.await(Math.max(1, timeoutMs) + 1_000, TimeUnit.MILLISECONDS)) {
        if (handleRef.get() != null) {
          handleRef.get().cancel();
        }
        throw new IllegalStateException("命令执行超时：" + String.join(" ", command));
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("命令执行被中断。", error);
    }
    if (errorRef.get() != null) {
      throw new IllegalStateException(message(errorRef.get()), errorRef.get());
    }
    if (exitCode.get() != 0) {
      throw new IllegalStateException("命令执行失败：" + tail(output.toString(), 1000));
    }
    return new ExecResult(exitCode.get(), output.toString());
  }

  private List<Object> readJsonList(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return new ArrayList<>();
    }
    try {
      return objectMapper.readValue(rawJson, new TypeReference<>() {});
    } catch (JsonProcessingException error) {
      return new ArrayList<>();
    }
  }

  private Map<String, Object> readJsonMap(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(rawJson, new TypeReference<>() {});
    } catch (JsonProcessingException error) {
      return new LinkedHashMap<>();
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("微信插件配置序列化失败。", error);
    }
  }

  private static void append(StringBuilder output, String chunk) {
    if (chunk != null && !chunk.isBlank()) {
      output.append(chunk);
      if (!chunk.endsWith("\n")) {
        output.append('\n');
      }
    }
  }

  private static String tail(String value, int maxLength) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(normalized.length() - maxLength);
  }

  private static String message(Throwable error) {
    if (error.getMessage() == null || error.getMessage().isBlank()) {
      return String.valueOf(error);
    }
    return error.getMessage();
  }

  private boolean isRunningTask(String status) {
    return "installing".equals(status) || "uninstalling".equals(status) || "upgrading".equals(status);
  }

  private PublicWechatPluginStatus runningStatus(String status, String outputSnippet, String message) {
    return new PublicWechatPluginStatus(
        false,
        "",
        "",
        false,
        status,
        message,
        defaultString(outputSnippet),
        Instant.now().toString()
    );
  }

  private PublicWechatPluginStatus missingStatus(String message) {
    return new PublicWechatPluginStatus(
        false,
        "",
        "",
        false,
        "missing",
        defaultString(message),
        "",
        Instant.now().toString()
    );
  }

  private PublicWechatPluginStatus failedStatus(String prefix, String message, String outputSnippet) {
    return new PublicWechatPluginStatus(
        false,
        "",
        "",
        false,
        "failed",
        prefix + defaultString(message),
        defaultString(outputSnippet),
        Instant.now().toString()
    );
  }

  private void publish(InstanceEntity instance, PublicWechatPluginStatus status) {
    eventPublisher.publishWechatPluginUpdated(instance.getId(), status);
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  private static Executor defaultExecutor() {
    return Executors.newCachedThreadPool(task -> {
      Thread thread = new Thread(task, "wechat-plugin-" + System.nanoTime());
      thread.setDaemon(true);
      return thread;
    });
  }

  private record ExecResult(int exitCode, String output) {}

  @FunctionalInterface
  private interface PluginTaskSuccess {
    PublicWechatPluginStatus complete(String outputSnippet);
  }
}
