package com.clawbotforall.externalapi;

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
import java.util.concurrent.CompletableFuture;
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
public class ApiChannelPluginService {
  private static final Logger log = LoggerFactory.getLogger(ApiChannelPluginService.class);
  private static final String PLUGIN_ID = "claw-manager-api";
  private static final String PACKAGE_NAME = "@claw-manager/openclaw-api-channel";
  private static final String NPM_SPEC = "npm:" + PACKAGE_NAME;
  private static final String NPM_PROJECT_PREFIX = "claw-manager-openclaw-api-channel";
  private static final String REGISTRY_URL = "https://registry.npmjs.org/%40claw-manager%2Fopenclaw-api-channel";
  private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+(?:\\.\\d+){1,3}(?:[-+][0-9A-Za-z.-]+)?");
  private static final long TASK_TIMEOUT_MS = 10 * 60 * 1000L;
  private static final long VERSION_CACHE_TTL_MS = 60 * 60 * 1000L;

  private final OpenClawRuntime openClawRuntime;
  private final InstanceCommandService commandService;
  private final InstanceFileService fileService;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final PluginOperationCoordinator operationCoordinator;
  private final Executor executor;
  private final Supplier<List<String>> versionSupplier;
  private final ConcurrentMap<String, PublicApiChannelPluginStatus> taskStatuses = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> taskJobs = new ConcurrentHashMap<>();
  private final AtomicReference<CachedVersions> cachedVersions = new AtomicReference<>();

  @Autowired
  public ApiChannelPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      PluginOperationCoordinator operationCoordinator
  ) {
    this(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        objectMapper,
        operationCoordinator,
        Executors.newCachedThreadPool(task -> {
          Thread thread = new Thread(task, "api-channel-plugin-" + System.nanoTime());
          thread.setDaemon(true);
          return thread;
        }),
        () -> fetchVersionsFromNpm(objectMapper)
    );
  }

  ApiChannelPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      PluginOperationCoordinator operationCoordinator,
      Executor executor,
      Supplier<List<String>> versionSupplier
  ) {
    this.openClawRuntime = openClawRuntime;
    this.commandService = commandService;
    this.fileService = fileService;
    this.mutationMapper = mutationMapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.operationCoordinator = operationCoordinator;
    this.executor = executor;
    this.versionSupplier = versionSupplier;
  }

  public boolean isInstalled(InstanceEntity instance) {
    return !currentVersion(instance).isBlank();
  }

  public PublicApiChannelPluginStatus status(InstanceEntity instance, boolean checkLatest) {
    PublicApiChannelPluginStatus running = taskStatuses.get(instance.getId());
    if (running != null && isRunning(running.status())) {
      return running;
    }
    String currentVersion = currentVersion(instance);
    boolean installed = !currentVersion.isBlank();
    String latest = installed && checkLatest ? cachedLatestVersion() : "";
    boolean upgradable = installed && !latest.isBlank() && compareVersion(latest, currentVersion) > 0;
    return new PublicApiChannelPluginStatus(
        installed,
        currentVersion,
        latest,
        upgradable,
        installed ? "installed" : "missing",
        installed ? "API Channel 插件已安装。" : "API Channel 插件尚未安装。",
        "",
        Instant.now().toString()
    );
  }

  public ApiChannelPluginVersions versions(boolean forceRefresh) {
    CachedVersions cached = cachedVersions.get();
    if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.loadedAtMs() < VERSION_CACHE_TTL_MS) {
      return publicVersions(cached.versions());
    }
    try {
      List<String> versions = normalizeVersions(versionSupplier.get());
      cachedVersions.set(new CachedVersions(versions, System.currentTimeMillis()));
      return publicVersions(versions);
    } catch (RuntimeException error) {
      log.warn("API Channel 插件版本读取失败：{}", message(error));
      return cached == null ? publicVersions(List.of()) : publicVersions(cached.versions());
    }
  }

  public PublicApiChannelPluginStatus startInstall(InstanceEntity instance, String version) {
    requireRunning(instance);
    String targetVersion = resolveInstallVersion(version);
    return startTask(instance, "installing", "API Channel 插件安装已开始。", "API Channel 插件安装失败：", () -> {
      runCommand(instance, installCommand(targetVersion));
      enablePlugin(instance);
      return successStatus(instance, "API Channel 插件安装完成。如 Gateway 未加载该插件，请重启 Gateway。", "");
    });
  }

  public PublicApiChannelPluginStatus startUpgrade(InstanceEntity instance, String version) {
    requireRunning(instance);
    String targetVersion = resolveInstallVersion(version);
    return startTask(instance, "upgrading", "API Channel 插件升级已开始。", "API Channel 插件升级失败：", () -> {
      runCommand(instance, installCommand(targetVersion));
      enablePlugin(instance);
      return successStatus(instance, "API Channel 插件升级完成。如 Gateway 未加载该插件，请重启 Gateway。", "");
    });
  }

  public PublicApiChannelPluginStatus startReinstall(InstanceEntity instance, String version) {
    requireRunning(instance);
    String targetVersion = resolveInstallVersion(version);
    return startTask(instance, "reinstalling", "API Channel 插件重新安装已开始。", "API Channel 插件重新安装失败：", () -> {
      runCommand(instance, installCommand(targetVersion));
      enablePlugin(instance);
      return successStatus(instance, "API Channel 插件重新安装完成。如 Gateway 未加载该插件，请重启 Gateway。", "");
    });
  }

  public PublicApiChannelPluginStatus startUninstall(InstanceEntity instance) {
    requireRunning(instance);
    return startTask(instance, "uninstalling", "API Channel 插件卸载已开始。", "API Channel 插件卸载失败：", () -> {
      runCommand(instance, List.of("openclaw", "plugins", "uninstall", PLUGIN_ID, "--force"));
      disablePlugin(instance);
      return new PublicApiChannelPluginStatus(false, "", "", false, "missing", "API Channel 插件已卸载。", "", Instant.now().toString());
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
    if (!operationCoordinator.tryStart(instanceId, "API Channel 插件")) {
      taskJobs.remove(instanceId);
      throw new ApiException(HttpStatus.CONFLICT, operationCoordinator.currentOwner(instanceId) + "正在执行，请等待当前插件任务完成后再操作。");
    }
    PublicApiChannelPluginStatus started = runningStatus(status, startedMessage);
    taskStatuses.put(instanceId, started);
    try {
      executor.execute(() -> {
        try {
          PublicApiChannelPluginStatus completed = task.run();
          taskStatuses.put(instanceId, completed);
        } catch (RuntimeException error) {
          taskStatuses.put(instanceId, failedStatus(failurePrefix + message(error), ""));
        } finally {
          taskJobs.remove(instanceId);
          operationCoordinator.finish(instanceId, "API Channel 插件");
        }
      });
    } catch (RuntimeException error) {
      taskJobs.remove(instanceId);
      operationCoordinator.finish(instanceId, "API Channel 插件");
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
    if (!normalized.isBlank()) {
      return normalized;
    }
    String latest = versions(false).latest();
    if (latest.isBlank()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "未能获取 API Channel 插件版本。");
    }
    return latest;
  }

  private void requireRunning(InstanceEntity instance) {
    RuntimeState state = openClawRuntime.inspectInstance(instance);
    if (!state.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "请先启动该 OpenClaw 实例，再安装 API Channel 插件。");
    }
  }

  private PublicApiChannelPluginStatus successStatus(InstanceEntity instance, String message, String output) {
    return new PublicApiChannelPluginStatus(true, currentVersion(instance), cachedLatestVersion(), false, "installed", message, output, Instant.now().toString());
  }

  private PublicApiChannelPluginStatus runningStatus(String status, String message) {
    return new PublicApiChannelPluginStatus(false, "", cachedLatestVersion(), false, status, message, "", Instant.now().toString());
  }

  private PublicApiChannelPluginStatus failedStatus(String message, String output) {
    return new PublicApiChannelPluginStatus(false, "", cachedLatestVersion(), false, "failed", message, output, Instant.now().toString());
  }

  private boolean isRunning(String status) {
    return "installing".equals(status) || "upgrading".equals(status) || "reinstalling".equals(status) || "uninstalling".equals(status);
  }

  private String currentVersion(InstanceEntity instance) {
    InstancePaths paths = fileService.paths(instance.getId());
    Path projectsDir = paths.homeDir().resolve(".openclaw").resolve("npm").resolve("projects");
    String npmVersion = currentNpmProjectVersion(projectsDir);
    if (!npmVersion.isBlank()) {
      return npmVersion;
    }
    return readNpmProjectVersion(paths.homeDir()
        .resolve(".openclaw")
        .resolve("extensions")
        .resolve(PLUGIN_ID)
        .resolve("package.json"));
  }

  private String currentNpmProjectVersion(Path projectsDir) {
    if (!Files.isDirectory(projectsDir)) {
      return "";
    }
    try (Stream<Path> projects = Files.find(projectsDir, 2, (path, attrs) -> attrs.isRegularFile() && "package.json".equals(path.getFileName().toString()))) {
      return projects
          .map(this::readNpmProjectVersion)
          .filter(version -> !version.isBlank())
          .findFirst()
          .orElse("");
    } catch (IOException error) {
      return "";
    }
  }

  private String readNpmProjectVersion(Path packagePath) {
    try {
      JsonNode json = objectMapper.readTree(packagePath.toFile());
      if (PACKAGE_NAME.equals(json.path("name").asText(""))) {
        return json.path("version").asText("");
      }
      return normalizeNpmVersion(json.path("dependencies").path(PACKAGE_NAME).asText(""));
    } catch (IOException error) {
      return "";
    }
  }

  private String normalizeNpmVersion(String version) {
    String normalized = defaultString(version).trim();
    if (normalized.startsWith("npm:")) {
      normalized = normalized.substring("npm:".length());
    }
    int atIndex = normalized.lastIndexOf('@');
    if (atIndex > 0 && atIndex + 1 < normalized.length()) {
      return normalized.substring(atIndex + 1);
    }
    return normalized;
  }

  private void runCommand(InstanceEntity instance, List<String> command) {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicReference<Integer> exitCode = new AtomicReference<>(Integer.MIN_VALUE);
    StringBuilder output = new StringBuilder();
    openClawRuntime.startExec(instance, command, TASK_TIMEOUT_MS, Map.of(), new RuntimeExecListener() {
      @Override
      public void onOutput(String chunk) {
        output.append(defaultString(chunk));
      }

      @Override
      public void onComplete(int code) {
        exitCode.set(code);
        done.countDown();
      }

      @Override
      public void onTimeout() {
        error.set(new IllegalStateException("API Channel 插件命令执行超时。"));
        done.countDown();
      }

      @Override
      public void onError(Throwable err) {
        error.set(err);
        done.countDown();
      }
    });
    try {
      if (!done.await(TASK_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("API Channel 插件命令执行超时。");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("API Channel 插件命令被中断。", interrupted);
    }
    if (error.get() != null) {
      throw new IllegalStateException(message(error.get()), error.get());
    }
    if (exitCode.get() != 0) {
      throw new IllegalStateException("退出码 " + exitCode.get() + "：" + tail(output.toString()));
    }
  }

  private List<Object> readJsonList(String json) {
    try {
      if (json == null || json.isBlank()) {
        return new ArrayList<>();
      }
      return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
    } catch (IOException error) {
      return new ArrayList<>();
    }
  }

  private Map<String, Object> readJsonMap(String json) {
    try {
      if (json == null || json.isBlank()) {
        return new LinkedHashMap<>();
      }
      return objectMapper.readValue(json, objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    } catch (IOException error) {
      return new LinkedHashMap<>();
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (IOException error) {
      throw new IllegalStateException("插件配置序列化失败。", error);
    }
  }

  private ApiChannelPluginVersions publicVersions(List<String> versions) {
    if (versions == null || versions.isEmpty()) {
      return new ApiChannelPluginVersions("", List.of());
    }
    return new ApiChannelPluginVersions(versions.getFirst(), versions.stream().limit(5).toList());
  }

  private String cachedLatestVersion() {
    CachedVersions cached = cachedVersions.get();
    return cached == null || cached.versions().isEmpty() ? "" : cached.versions().getFirst();
  }

  private List<String> normalizeVersions(List<String> raw) {
    return (raw == null ? List.<String>of() : raw).stream()
        .map(ApiChannelPluginService::defaultString)
        .map(String::trim)
        .filter(version -> !version.isBlank())
        .filter(version -> VERSION_PATTERN.matcher(version).matches())
        .distinct()
        .sorted(ApiChannelPluginService::compareVersionDesc)
        .toList();
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
    } catch (IOException error) {
      throw new IllegalStateException("读取 API Channel 插件版本失败：" + error.getMessage(), error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("读取 API Channel 插件版本被中断。", error);
    }
  }

  private static int compareVersionDesc(String left, String right) {
    return -compareVersion(left, right);
  }

  private static int compareVersion(String left, String right) {
    String[] leftParts = defaultString(left).split("[.+-]");
    String[] rightParts = defaultString(right).split("[.+-]");
    int length = Math.max(leftParts.length, rightParts.length);
    for (int i = 0; i < length; i++) {
      int leftValue = i < leftParts.length ? parseInt(leftParts[i]) : 0;
      int rightValue = i < rightParts.length ? parseInt(rightParts[i]) : 0;
      if (leftValue != rightValue) {
        return Integer.compare(leftValue, rightValue);
      }
    }
    return defaultString(left).compareTo(defaultString(right));
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  private static String message(Throwable error) {
    Throwable cause = error.getCause() == null ? error : error.getCause();
    return cause.getMessage() == null || cause.getMessage().isBlank() ? String.valueOf(cause) : cause.getMessage();
  }

  private static String tail(String value) {
    String normalized = defaultString(value).trim();
    return normalized.length() <= 1000 ? normalized : normalized.substring(normalized.length() - 1000);
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  public record ApiChannelPluginVersions(String latest, List<String> versions) {}

  private record CachedVersions(List<String> versions, long loadedAtMs) {}

  private interface PluginTask {
    PublicApiChannelPluginStatus run();
  }
}
