package com.clawbotforall.miniapp;

import com.clawbotforall.externalapi.ApiChannelPluginService.ApiChannelPluginVersions;
import com.clawbotforall.externalapi.PublicApiChannelPluginStatus;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.plugin.PluginOperationCoordinator;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MiniappBridgePluginService {
  private static final Logger log = LoggerFactory.getLogger(MiniappBridgePluginService.class);
  private static final String PLUGIN_ID = "miniapp-bridge";
  private static final String PACKAGE_NAME = "@claw-manager/miniapp-bridge-plugin";
  private static final String NPM_SPEC = "npm:" + PACKAGE_NAME;
  private static final String NPM_PROJECT_PREFIX = "claw-manager-miniapp-bridge-plugin";
  private static final String REGISTRY_URL = "https://registry.npmjs.org/%40claw-manager%2Fminiapp-bridge-plugin";
  private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+(?:\\.\\d+){1,3}(?:[-+][0-9A-Za-z.-]+)?");
  private static final long TASK_TIMEOUT_MS = 10 * 60 * 1000L;
  private static final long VERSION_CACHE_TTL_MS = 60 * 60 * 1000L;

  private final OpenClawRuntime runtime;
  private final InstanceCommandService commandService;
  private final InstanceFileService fileService;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final PluginOperationCoordinator coordinator;
  private final Executor executor;
  private final Supplier<List<String>> versionSupplier;
  private final ConcurrentMap<String, PublicApiChannelPluginStatus> taskStatuses = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> taskJobs = new ConcurrentHashMap<>();
  private final AtomicReference<CachedVersions> cachedVersions = new AtomicReference<>();

  @Autowired
  public MiniappBridgePluginService(
      OpenClawRuntime runtime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      PluginOperationCoordinator coordinator
  ) {
    this(
        runtime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        objectMapper,
        coordinator,
        Executors.newCachedThreadPool(task -> {
          Thread thread = new Thread(task, "miniapp-bridge-plugin-" + System.nanoTime());
          thread.setDaemon(true);
          return thread;
        }),
        () -> fetchVersionsFromNpm(objectMapper)
    );
  }

  MiniappBridgePluginService(
      OpenClawRuntime runtime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      PluginOperationCoordinator coordinator,
      Executor executor,
      Supplier<List<String>> versionSupplier
  ) {
    this.runtime = runtime;
    this.commandService = commandService;
    this.fileService = fileService;
    this.mutationMapper = mutationMapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.coordinator = coordinator;
    this.executor = executor;
    this.versionSupplier = versionSupplier;
  }

  public PublicApiChannelPluginStatus status(InstanceEntity instance, boolean checkLatest) {
    PublicApiChannelPluginStatus running = taskStatuses.get(instance.getId());
    if (running != null && isRunning(running.status())) {
      return running;
    }
    String currentVersion = currentVersion(instance);
    boolean installed = !currentVersion.isBlank();
    String latestVersion = installed && checkLatest ? cachedLatestVersion() : "";
    boolean upgradable = installed
        && !latestVersion.isBlank()
        && compareVersion(latestVersion, currentVersion) > 0;
    return new PublicApiChannelPluginStatus(
        installed,
        currentVersion,
        latestVersion,
        upgradable,
        installed ? "installed" : "missing",
        installed ? "小程序 Bridge 插件已安装。" : "小程序 Bridge 插件尚未安装。",
        "",
        Instant.now().toString()
    );
  }

  public ApiChannelPluginVersions versions() {
    return versions(false);
  }

  public ApiChannelPluginVersions versions(boolean forceRefresh) {
    CachedVersions cached = cachedVersions.get();
    if (!forceRefresh && cached != null
        && System.currentTimeMillis() - cached.loadedAtMs() < VERSION_CACHE_TTL_MS) {
      return publicVersions(cached.versions());
    }
    try {
      List<String> versions = normalizeVersions(versionSupplier.get());
      cachedVersions.set(new CachedVersions(versions, System.currentTimeMillis()));
      return publicVersions(versions);
    } catch (RuntimeException error) {
      log.warn("小程序 Bridge 插件版本读取失败：{}", message(error));
      return cached == null ? publicVersions(List.of()) : publicVersions(cached.versions());
    }
  }

  public PublicApiChannelPluginStatus startInstall(InstanceEntity instance, String version) {
    String targetVersion = resolveInstallVersion(version);
    requireRunning(instance);
    return startTask(instance, "installing", "小程序 Bridge 插件安装已开始。", "小程序 Bridge 插件安装失败：", () -> {
      String output = runCommand(instance, installCommand(targetVersion));
      enablePlugin(instance);
      return successStatus(instance, "小程序 Bridge 插件安装完成。如 Gateway 未加载该插件，请重启 Gateway。", output);
    });
  }

  public PublicApiChannelPluginStatus startUpgrade(InstanceEntity instance, String version) {
    String targetVersion = resolveInstallVersion(version);
    requireRunning(instance);
    return startTask(instance, "upgrading", "小程序 Bridge 插件升级已开始。", "小程序 Bridge 插件升级失败：", () -> {
      String output = runCommand(instance, installCommand(targetVersion));
      enablePlugin(instance);
      return successStatus(instance, "小程序 Bridge 插件升级完成。如 Gateway 未加载新版本，请重启 Gateway。", output);
    });
  }

  public PublicApiChannelPluginStatus startReinstall(InstanceEntity instance, String version) {
    String targetVersion = resolveInstallVersion(version);
    requireRunning(instance);
    return startTask(instance, "reinstalling", "小程序 Bridge 插件重新安装已开始。", "小程序 Bridge 插件重新安装失败：", () -> {
      String output = runCommand(instance, installCommand(targetVersion));
      enablePlugin(instance);
      return successStatus(instance, "小程序 Bridge 插件重新安装完成。如 Gateway 未加载该插件，请重启 Gateway。", output);
    });
  }

  public PublicApiChannelPluginStatus startUninstall(InstanceEntity instance) {
    requireRunning(instance);
    return startTask(instance, "uninstalling", "小程序 Bridge 插件卸载已开始。", "小程序 Bridge 插件卸载失败：", () -> {
      String output = runCommand(instance, List.of("openclaw", "plugins", "uninstall", PLUGIN_ID, "--force"));
      disablePlugin(instance);
      return new PublicApiChannelPluginStatus(
          false, "", cachedLatestVersion(), false, "missing", "小程序 Bridge 插件已卸载。", output, Instant.now().toString());
    });
  }

  private PublicApiChannelPluginStatus startTask(
      InstanceEntity instance,
      String status,
      String startedMessage,
      String failurePrefix,
      PluginTask task
  ) {
    String instanceId = instance.getId();
    PublicApiChannelPluginStatus existing = taskStatuses.get(instanceId);
    if (taskJobs.putIfAbsent(instanceId, true) != null) {
      return existing == null ? runningStatus(status, startedMessage) : existing;
    }
    if (!coordinator.tryStart(instanceId, "小程序 Bridge 插件")) {
      taskJobs.remove(instanceId);
      throw new ApiException(
          HttpStatus.CONFLICT,
          coordinator.currentOwner(instanceId) + "正在执行，请等待当前插件任务完成后再操作。"
      );
    }
    PublicApiChannelPluginStatus started = runningStatus(status, startedMessage);
    taskStatuses.put(instanceId, started);
    publish(instance, started);
    try {
      executor.execute(() -> {
        try {
          PublicApiChannelPluginStatus completed = task.run();
          taskStatuses.put(instanceId, completed);
          publish(instance, completed);
        } catch (RuntimeException error) {
          PublicApiChannelPluginStatus failed = failedStatus(failurePrefix + message(error), output(error));
          taskStatuses.put(instanceId, failed);
          publish(instance, failed);
        } finally {
          taskJobs.remove(instanceId);
          coordinator.finish(instanceId, "小程序 Bridge 插件");
        }
      });
    } catch (RuntimeException error) {
      taskJobs.remove(instanceId);
      coordinator.finish(instanceId, "小程序 Bridge 插件");
      throw error;
    }
    return started;
  }

  private void enablePlugin(InstanceEntity instance) {
    List<Object> allow = readJsonList(instance.getPluginsAllow());
    if (!allow.contains(PLUGIN_ID)) {
      allow = new ArrayList<>(allow);
      allow.add(PLUGIN_ID);
    }
    Map<String, Object> entries = readJsonMap(instance.getPluginsEntries());
    entries.put(PLUGIN_ID, Map.of("enabled", true));
    updatePluginConfig(instance, allow, entries);
  }

  private void disablePlugin(InstanceEntity instance) {
    List<Object> allow = new ArrayList<>(readJsonList(instance.getPluginsAllow()));
    allow.removeIf(item -> PLUGIN_ID.equals(String.valueOf(item)));
    Map<String, Object> entries = new LinkedHashMap<>(readJsonMap(instance.getPluginsEntries()));
    entries.remove(PLUGIN_ID);
    updatePluginConfig(instance, allow, entries);
  }

  private void updatePluginConfig(InstanceEntity instance, List<Object> allow, Map<String, Object> entries) {
    String now = Instant.now().toString();
    String allowJson = writeJson(allow);
    String entriesJson = writeJson(entries);
    mutationMapper.updateInstancePlugins(instance.getId(), allowJson, entriesJson, now);
    instance.setPluginsAllow(allowJson);
    instance.setPluginsEntries(entriesJson);
    instance.setUpdatedAt(now);
    fileService.writeInstanceFiles(instance, commandService.listModels(instance.getId()));
  }

  private List<String> installCommand(String version) {
    return List.of("openclaw", "plugins", "install", NPM_SPEC + "@" + version, "--force");
  }

  private String resolveInstallVersion(String version) {
    String normalized = defaultString(version).trim();
    if (normalized.isBlank() || "latest".equalsIgnoreCase(normalized)) {
      String latest = versions(false).latest();
      if (latest.isBlank()) {
        throw new ApiException(HttpStatus.BAD_GATEWAY, "未能获取小程序 Bridge 插件版本。");
      }
      return latest;
    }
    if (!VERSION_PATTERN.matcher(normalized).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "小程序 Bridge 插件版本格式不正确。");
    }
    return normalized;
  }

  private void requireRunning(InstanceEntity instance) {
    RuntimeState state = runtime.inspectInstance(instance);
    if (!state.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "请先启动该 OpenClaw 实例，再操作小程序 Bridge 插件。");
    }
  }

  private PublicApiChannelPluginStatus successStatus(InstanceEntity instance, String message, String output) {
    return new PublicApiChannelPluginStatus(
        true,
        currentVersion(instance),
        cachedLatestVersion(),
        false,
        "installed",
        message,
        tail(output),
        Instant.now().toString()
    );
  }

  private PublicApiChannelPluginStatus runningStatus(String status, String message) {
    return new PublicApiChannelPluginStatus(
        false, "", cachedLatestVersion(), false, status, message, "", Instant.now().toString());
  }

  private PublicApiChannelPluginStatus failedStatus(String message, String output) {
    return new PublicApiChannelPluginStatus(
        false, "", cachedLatestVersion(), false, "failed", message, tail(output), Instant.now().toString());
  }

  private String currentVersion(InstanceEntity instance) {
    try {
      InstancePaths paths = fileService.paths(instance.getId());
      Path extension = paths.homeDir().resolve(".openclaw").resolve("extensions").resolve(PLUGIN_ID).resolve("package.json");
      if (Files.isRegularFile(extension)) {
        String version = readVersionFromPackage(extension);
        if (!version.isBlank()) {
          return version;
        }
      }
      Path projectsDir = paths.homeDir().resolve(".openclaw").resolve("npm").resolve("projects");
      if (!Files.isDirectory(projectsDir)) {
        return "";
      }
      try (Stream<Path> packages = Files.find(
          projectsDir,
          2,
          (path, attrs) -> attrs.isRegularFile() && "package.json".equals(path.getFileName().toString())
      )) {
        return packages
            .filter(path -> path.getParent().getFileName().toString().startsWith(NPM_PROJECT_PREFIX))
            .map(this::readVersionFromPackage)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse("");
      }
    } catch (IOException | RuntimeException error) {
      return "";
    }
  }

  private String readVersionFromPackage(Path packagePath) {
    try {
      JsonNode json = objectMapper.readTree(packagePath.toFile());
      if (PACKAGE_NAME.equals(json.path("name").asText(""))) {
        return json.path("version").asText("");
      }
      return normalizeDependencyVersion(json.path("dependencies").path(PACKAGE_NAME).asText(""));
    } catch (IOException error) {
      return "";
    }
  }

  private String normalizeDependencyVersion(String value) {
    String normalized = defaultString(value).trim();
    if (normalized.startsWith("npm:")) {
      normalized = normalized.substring("npm:".length());
      int versionSeparator = normalized.lastIndexOf('@');
      return versionSeparator >= 0 && versionSeparator + 1 < normalized.length()
          ? normalized.substring(versionSeparator + 1)
          : "";
    }
    return normalized;
  }

  private String runCommand(InstanceEntity instance, List<String> command) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicReference<Integer> exitCode = new AtomicReference<>();
    StringBuilder output = new StringBuilder();
    runtime.startExec(instance, command, TASK_TIMEOUT_MS, Map.of(), new RuntimeExecListener() {
      @Override
      public void onOutput(String chunk) {
        synchronized (output) {
          output.append(defaultString(chunk));
        }
      }

      @Override
      public void onComplete(int code) {
        exitCode.set(code);
        latch.countDown();
      }

      @Override
      public void onTimeout() {
        error.set(new PluginCommandException("命令执行超时。", tail(output.toString())));
        latch.countDown();
      }

      @Override
      public void onError(Throwable value) {
        error.set(new PluginCommandException(message(value), tail(output.toString()), value));
        latch.countDown();
      }
    });
    try {
      if (!latch.await(TASK_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS)) {
        throw new PluginCommandException("命令执行超时。", tail(output.toString()));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new PluginCommandException("命令被中断。", tail(output.toString()), interrupted);
    }
    if (error.get() != null) {
      Throwable cause = error.get();
      if (cause instanceof RuntimeException runtimeError) {
        throw runtimeError;
      }
      throw new PluginCommandException(message(cause), tail(output.toString()), cause);
    }
    if (exitCode.get() == null || exitCode.get() != 0) {
      throw new PluginCommandException("插件命令退出码：" + exitCode.get(), tail(output.toString()));
    }
    return tail(output.toString());
  }

  private List<Object> readJsonList(String json) {
    if (json == null || json.isBlank()) {
      return new ArrayList<>();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (IOException error) {
      return new ArrayList<>();
    }
  }

  private Map<String, Object> readJsonMap(String json) {
    if (json == null || json.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (IOException error) {
      return new LinkedHashMap<>();
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (IOException error) {
      throw new IllegalStateException("小程序 Bridge 插件配置 JSON 写入失败。", error);
    }
  }

  private ApiChannelPluginVersions publicVersions(List<String> versions) {
    List<String> recent = versions == null ? List.of() : versions.stream().limit(5).toList();
    return new ApiChannelPluginVersions(recent.isEmpty() ? "" : recent.getFirst(), recent);
  }

  private String cachedLatestVersion() {
    return versions(false).latest();
  }

  private static List<String> normalizeVersions(List<String> versions) {
    List<String> normalized = new ArrayList<>();
    if (versions != null) {
      versions.stream()
          .filter(version -> version != null && VERSION_PATTERN.matcher(version).matches())
          .distinct()
          .forEach(normalized::add);
    }
    normalized.sort(MiniappBridgePluginService::compareVersionDesc);
    return normalized;
  }

  private static List<String> fetchVersionsFromNpm(ObjectMapper objectMapper) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder(URI.create(REGISTRY_URL)).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofString()
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("npm registry 返回 HTTP " + response.statusCode());
      }
      JsonNode versions = objectMapper.readTree(response.body()).path("versions");
      if (!versions.isObject()) {
        return List.of();
      }
      List<String> result = new ArrayList<>();
      versions.fieldNames().forEachRemaining(result::add);
      return result;
    } catch (Exception error) {
      throw new IllegalStateException("小程序 Bridge 插件 npm 版本读取失败。", error);
    }
  }

  private static int compareVersionDesc(String left, String right) {
    return -compareVersion(left, right);
  }

  private static int compareVersion(String left, String right) {
    String[] a = left.split("[.-]");
    String[] b = right.split("[.-]");
    int max = Math.max(a.length, b.length);
    for (int index = 0; index < max; index += 1) {
      int ai = index < a.length ? parseInt(a[index]) : 0;
      int bi = index < b.length ? parseInt(b[index]) : 0;
      if (ai != bi) {
        return Integer.compare(ai, bi);
      }
    }
    return left.compareTo(right);
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  private static boolean isRunning(String status) {
    return "installing".equals(status)
        || "upgrading".equals(status)
        || "reinstalling".equals(status)
        || "uninstalling".equals(status);
  }

  private void publish(InstanceEntity instance, PublicApiChannelPluginStatus status) {
    eventPublisher.publishMiniappBridgePluginUpdated(instance.getId(), status);
  }

  private static String output(Throwable error) {
    return error instanceof PluginCommandException commandError ? commandError.output() : "";
  }

  private static String message(Throwable error) {
    return error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName()
        : error.getMessage();
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  private static String tail(String value) {
    if (value == null || value.length() <= 4000) {
      return defaultString(value);
    }
    return value.substring(value.length() - 4000);
  }

  private record CachedVersions(List<String> versions, long loadedAtMs) {}

  private interface PluginTask {
    PublicApiChannelPluginStatus run();
  }

  private static final class PluginCommandException extends IllegalStateException {
    private final String output;

    private PluginCommandException(String message, String output) {
      super(message);
      this.output = output;
    }

    private PluginCommandException(String message, String output, Throwable cause) {
      super(message, cause);
      this.output = output;
    }

    private String output() {
      return output;
    }
  }
}
