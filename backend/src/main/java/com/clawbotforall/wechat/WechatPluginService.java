package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.plugin.PluginOperationCoordinator;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 按 1Panel 的 OpenClaw 插件安装方式管理微信插件。
 */
@Service
public class WechatPluginService {

  private static final Logger log = LoggerFactory.getLogger(WechatPluginService.class);

  private static final String WECHAT_PLUGIN_TYPE = "weixin";
  private static final String WECHAT_PLUGIN_ID = "openclaw-weixin";
  private static final String WECHAT_PLUGIN_SPEC = "@claw-manager/openclaw-weixin";
  private static final String WECHAT_PLUGIN_NPM_SPEC = "npm:" + WECHAT_PLUGIN_SPEC;
  private static final String WECHAT_PLUGIN_NPM_PROJECT_PREFIX = "claw-manager-openclaw-weixin";
  private static final String WECHAT_PLUGIN_REGISTRY_URL = "https://registry.npmjs.org/%40claw-manager%2Fopenclaw-weixin";
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
  private final Supplier<List<String>> clawManagerVersionSupplier;
  private final ConcurrentMap<String, PublicWechatPluginStatus> taskStatuses = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> taskJobs = new ConcurrentHashMap<>();
  private final AtomicReference<CachedVersions> cachedVersions = new AtomicReference<>();
  private final AtomicReference<CompletableFuture<List<String>>> versionRefresh = new AtomicReference<>();

  @Autowired
  public WechatPluginService(
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
        defaultExecutor(),
        () -> fetchClawManagerVersionsFromNpm(objectMapper),
        operationCoordinator
    );
  }

  WechatPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Executor executor,
      Supplier<List<String>> clawManagerVersionSupplier
  ) {
    this(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        objectMapper,
        executor,
        clawManagerVersionSupplier,
        new PluginOperationCoordinator()
    );
  }

  WechatPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Executor executor,
      Supplier<List<String>> clawManagerVersionSupplier,
      PluginOperationCoordinator operationCoordinator
  ) {
    this.openClawRuntime = openClawRuntime;
    this.commandService = commandService;
    this.fileService = fileService;
    this.mutationMapper = mutationMapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.operationCoordinator = operationCoordinator;
    this.executor = executor;
    this.clawManagerVersionSupplier = clawManagerVersionSupplier;
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

  public WechatPluginVersions versions() {
    CachedVersions cached = cachedVersions.get();
    if (isFresh(cached)) {
      return publicVersions(cached.versions());
    }
    if (cached != null) {
      refreshClawManagerVersionsAsync();
      return publicVersions(cached.versions());
    }
    try {
      return publicVersions(refreshClawManagerVersionsBlocking());
    } catch (RuntimeException error) {
      log.warn("Claw Manager 微信插件版本读取失败，返回空版本列表：{}", message(error));
      return publicVersions(List.of());
    }
  }

  private PublicWechatPluginStatus detectedStatus(InstanceEntity instance, boolean checkLatest) {
    String currentVersion = currentVersion(instance);
    boolean installed = !currentVersion.isBlank();
    String latestVersion = "";
    if (installed && checkLatest) {
      latestVersion = cachedLatestVersion();
    }
    boolean upgradable = installed && !latestVersion.isBlank() && compareVersion(latestVersion, currentVersion) > 0;
    return new PublicWechatPluginStatus(
        installed,
        currentVersion,
        latestVersion,
        upgradable,
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
    return startInstall(instance, "");
  }

  public PublicWechatPluginStatus startInstall(InstanceEntity instance, String version) {
    PublicWechatPluginStatus running = runningTask(instance, "installing", "微信插件正在安装。");
    if (running != null) {
      return running;
    }
    requireNoOtherPluginTask(instance);
    if (isWechatPluginInstalled(instance)) {
      throw new ApiException(HttpStatus.CONFLICT, "微信插件已安装，请使用重新安装或升级。");
    }
    String targetVersion = resolveInstallVersion(version);
    return startTask(
        instance,
        "installing",
        "微信插件安装已开始。",
        "微信插件正在安装。",
        "微信插件安装失败：",
        installCommand(targetVersion),
        output -> {
          enableWechatPlugin(instance);
          PublicWechatPluginStatus status = detectedStatus(instance, false);
          return new PublicWechatPluginStatus(
              status.installed(),
              status.currentVersion(),
              status.latestVersion(),
              status.upgradable(),
              status.installed() ? "installed" : "unknown",
              status.installed() ? "微信插件安装完成。如 Gateway 未加载该插件，请重启 Gateway。" : "微信插件命令已完成，但未检测到插件目录。",
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
    requireNoOtherPluginTask(instance);
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
    return startUpgrade(instance, "");
  }

  public PublicWechatPluginStatus startUpgrade(InstanceEntity instance, String version) {
    PublicWechatPluginStatus running = runningTask(instance, "upgrading", "微信插件正在升级。");
    if (running != null) {
      return running;
    }
    requireNoOtherPluginTask(instance);
    String currentVersion = currentVersion(instance);
    if (currentVersion.isBlank()) {
      PublicWechatPluginStatus missing = missingStatus("微信插件尚未安装，无法升级。");
      taskStatuses.put(instance.getId(), missing);
      publish(instance, missing);
      return missing;
    }
    String targetVersion = resolveUpgradeVersion(version, currentVersion);
    return startTask(
        instance,
        "upgrading",
        "微信插件升级已开始。",
        "微信插件正在升级。",
        "微信插件升级失败：",
        installCommand(targetVersion),
        output -> {
          enableWechatPlugin(instance);
          PublicWechatPluginStatus status = detectedStatus(instance, false);
          return new PublicWechatPluginStatus(
              status.installed(),
              status.currentVersion(),
              status.latestVersion(),
              status.upgradable(),
              status.installed() ? "installed" : "missing",
              status.installed() ? "微信插件升级完成。如 Gateway 未加载该插件，请重启 Gateway。" : "微信插件升级命令已完成，但未检测到插件目录。",
              output,
              Instant.now().toString()
          );
        }
    );
  }

  public PublicWechatPluginStatus startReinstall(InstanceEntity instance, String version) {
    PublicWechatPluginStatus running = runningTask(instance, "reinstalling", "微信插件正在重新安装。");
    if (running != null) {
      return running;
    }
    requireNoOtherPluginTask(instance);
    String currentVersion = currentVersion(instance);
    if (currentVersion.isBlank()) {
      PublicWechatPluginStatus missing = missingStatus("微信插件尚未安装，无法重新安装。");
      taskStatuses.put(instance.getId(), missing);
      publish(instance, missing);
      return missing;
    }
    String targetVersion = resolveReinstallVersion(version, currentVersion);
    return startTask(
        instance,
        "reinstalling",
        "微信插件重新安装已开始。",
        "微信插件正在重新安装。",
        "微信插件重新安装失败：",
        installCommand(targetVersion),
        output -> {
          enableWechatPlugin(instance);
          PublicWechatPluginStatus status = detectedStatus(instance, false);
          return new PublicWechatPluginStatus(
              status.installed(),
              status.currentVersion(),
              status.latestVersion(),
              status.upgradable(),
              status.installed() ? "installed" : "missing",
              status.installed() ? "微信插件重新安装完成。如 Gateway 未加载该插件，请重启 Gateway。" : "微信插件重新安装命令已完成，但未检测到插件目录。",
              output,
              Instant.now().toString()
          );
        }
    );
  }

  private List<String> installCommand(String version) {
    String spec = version == null || version.isBlank()
        ? WECHAT_PLUGIN_NPM_SPEC
        : WECHAT_PLUGIN_NPM_SPEC + "@" + version;
    return List.of("openclaw", "plugins", "install", spec, "--force");
  }

  private String resolveInstallVersion(String version) {
    String normalized = normalizeRequestedVersion(version);
    if (normalized.isBlank()) {
      return "";
    }
    requireClawManagerVersion(normalized);
    return normalized;
  }

  private String resolveReinstallVersion(String version, String currentVersion) {
    String normalized = normalizeRequestedVersion(version);
    if (normalized.isBlank()) {
      return currentVersion;
    }
    requireClawManagerVersion(normalized);
    return normalized;
  }

  private String resolveUpgradeVersion(String version, String currentVersion) {
    String normalized = normalizeRequestedVersion(version);
    String targetVersion = normalized.isBlank() ? latestClawManagerVersionForOperation() : normalized;
    requireClawManagerVersion(targetVersion);
    if (compareVersion(targetVersion, currentVersion) <= 0) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "目标版本必须高于当前版本。");
    }
    return targetVersion;
  }

  private void requireClawManagerVersion(String version) {
    if (!VERSION_PATTERN.matcher(version).matches() || !clawManagerVersions().contains(version)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "只能选择 Claw Manager 微信插件版本。");
    }
  }

  private String latestClawManagerVersionForOperation() {
    List<String> versions = clawManagerVersions();
    if (versions.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "未能获取 Claw Manager 微信插件版本。");
    }
    return versions.getFirst();
  }

  private static String normalizeRequestedVersion(String version) {
    String normalized = version == null ? "" : version.trim();
    return "latest".equalsIgnoreCase(normalized) ? "" : normalized;
  }

  private List<String> clawManagerVersions() {
    CachedVersions cached = cachedVersions.get();
    if (cached != null) {
      if (!isFresh(cached)) {
        refreshClawManagerVersionsAsync();
      }
      return cached.versions();
    }
    return refreshClawManagerVersionsBlocking();
  }

  private List<String> refreshClawManagerVersionsBlocking() {
    CompletableFuture<List<String>> existing = versionRefresh.get();
    if (existing != null) {
      return joinRefresh(existing);
    }
    CompletableFuture<List<String>> created = new CompletableFuture<>();
    if (!versionRefresh.compareAndSet(null, created)) {
      CompletableFuture<List<String>> winner = versionRefresh.get();
      return winner == null ? refreshClawManagerVersionsBlocking() : joinRefresh(winner);
    }
    try {
      List<String> versions = loadClawManagerVersions();
      created.complete(versions);
      return versions;
    } catch (RuntimeException error) {
      created.completeExceptionally(error);
      throw error;
    } finally {
      versionRefresh.compareAndSet(created, null);
    }
  }

  private void refreshClawManagerVersionsAsync() {
    CompletableFuture<List<String>> created = new CompletableFuture<>();
    if (!versionRefresh.compareAndSet(null, created)) {
      return;
    }
    try {
      executor.execute(() -> {
        try {
          created.complete(loadClawManagerVersions());
        } catch (RuntimeException error) {
          log.warn("Claw Manager 微信插件版本后台刷新失败：{}", message(error));
          created.completeExceptionally(error);
        } finally {
          versionRefresh.compareAndSet(created, null);
        }
      });
    } catch (RuntimeException error) {
      versionRefresh.compareAndSet(created, null);
      created.completeExceptionally(error);
      throw error;
    }
  }

  private List<String> loadClawManagerVersions() {
    long startedAt = System.nanoTime();
    List<String> versions = normalizeClawManagerVersions(clawManagerVersionSupplier.get());
    if (versions.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "未能获取 Claw Manager 微信插件版本。");
    }
    cachedVersions.set(new CachedVersions(versions, System.currentTimeMillis() + VERSION_CACHE_TTL_MS));
    log.info(
        "Claw Manager 微信插件版本缓存已刷新：latest={}, count={}, elapsedMs={}",
        versions.getFirst(),
        versions.size(),
        elapsedMs(startedAt)
    );
    return versions;
  }

  private List<String> normalizeClawManagerVersions(List<String> rawVersions) {
    return (rawVersions == null ? List.<String>of() : rawVersions).stream()
        .map(WechatPluginService::defaultString)
        .map(String::trim)
        .filter(version -> !version.isBlank())
        .filter(version -> VERSION_PATTERN.matcher(version).matches())
        .distinct()
        .sorted((left, right) -> compareVersion(right, left))
        .toList();
  }

  private List<String> joinRefresh(CompletableFuture<List<String>> future) {
    try {
      return future.join();
    } catch (CompletionException error) {
      if (error.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw error;
    }
  }

  private WechatPluginVersions publicVersions(List<String> versions) {
    if (versions == null || versions.isEmpty()) {
      return new WechatPluginVersions("", List.of());
    }
    return new WechatPluginVersions(
        versions.getFirst(),
        versions.stream().limit(5).toList()
    );
  }

  private String cachedLatestVersion() {
    CachedVersions cached = cachedVersions.get();
    if (cached == null || cached.versions().isEmpty()) {
      return "";
    }
    return cached.versions().getFirst();
  }

  private boolean isFresh(CachedVersions cached) {
    return cached != null && cached.expiresAtMs() > System.currentTimeMillis();
  }

  private PublicWechatPluginStatus runningTask(InstanceEntity instance, String fallbackStatus, String fallbackMessage) {
    requireRunning(instance);
    if (!taskJobs.containsKey(instance.getId())) {
      return null;
    }
    PublicWechatPluginStatus existing = taskStatuses.get(instance.getId());
    return existing == null ? runningStatus(fallbackStatus, "", fallbackMessage) : existing;
  }

  private void requireNoOtherPluginTask(InstanceEntity instance) {
    String owner = operationCoordinator.currentOwner(instance.getId());
    if (!owner.isBlank() && !"微信插件".equals(owner)) {
      throw new ApiException(HttpStatus.CONFLICT, owner + "正在执行，请等待当前插件任务完成后再操作。");
    }
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
    if (!operationCoordinator.tryStart(instanceId, "微信插件")) {
      taskJobs.remove(instanceId);
      throw new ApiException(HttpStatus.CONFLICT, operationCoordinator.currentOwner(instanceId) + "正在执行，请等待当前插件任务完成后再操作。");
    }
    PublicWechatPluginStatus started = runningStatus(runningStatus, "", startedMessage);
    taskStatuses.put(instanceId, started);
    publish(instance, started);
    try {
      executor.execute(() -> runTask(instance, runningStatus, progressMessage, failurePrefix, command, success));
    } catch (RuntimeException error) {
      taskJobs.remove(instanceId);
      operationCoordinator.finish(instanceId, "微信插件");
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
      operationCoordinator.finish(instanceId, "微信插件");
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
      String packageName = json.path("name").asText("");
      if (packageName.isBlank() || WECHAT_PLUGIN_SPEC.equals(packageName) || WECHAT_PLUGIN_ID.equals(packageName)) {
        return json.path("version").asText("");
      }
      return "";
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
    try (Stream<Path> projects = Files.list(projectsDir)) {
      String version = projects
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith(WECHAT_PLUGIN_NPM_PROJECT_PREFIX))
          .map(path -> path.resolve("package.json"))
          .filter(Files::isRegularFile)
          .map(this::readWechatNpmProjectVersion)
          .filter(candidate -> !candidate.isBlank())
          .findFirst()
          .orElse("");
      if (!version.isBlank()) {
        return version;
      }
    } catch (IOException error) {
      return "";
    }
    return fallbackNpmProjectPluginVersion(projectsDir);
  }

  private String fallbackNpmProjectPluginVersion(Path projectsDir) {
    try (Stream<Path> packages = Files.find(
        projectsDir,
        2,
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

  private static List<String> fetchClawManagerVersionsFromNpm(ObjectMapper objectMapper) {
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(WECHAT_PLUGIN_REGISTRY_URL))
          .timeout(Duration.ofSeconds(5))
          .GET()
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
      throw new IllegalStateException("读取 Claw Manager 微信插件版本失败：" + error.getMessage(), error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("读取 Claw Manager 微信插件版本被中断。", error);
    }
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
    return "installing".equals(status)
        || "uninstalling".equals(status)
        || "upgrading".equals(status)
        || "reinstalling".equals(status);
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

  private static long elapsedMs(long startedAtNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
  }

  private static int compareVersion(String left, String right) {
    String[] leftParts = versionCore(left).split("\\.");
    String[] rightParts = versionCore(right).split("\\.");
    int length = Math.max(leftParts.length, rightParts.length);
    for (int index = 0; index < length; index++) {
      int leftValue = numericPart(leftParts, index);
      int rightValue = numericPart(rightParts, index);
      if (leftValue != rightValue) {
        return Integer.compare(leftValue, rightValue);
      }
    }
    return versionQualifierRank(left) - versionQualifierRank(right);
  }

  private static String versionCore(String version) {
    String normalized = defaultString(version);
    int separator = normalized.indexOf('-');
    if (separator < 0) {
      separator = normalized.indexOf('+');
    }
    return separator < 0 ? normalized : normalized.substring(0, separator);
  }

  private static int numericPart(String[] parts, int index) {
    if (index >= parts.length) {
      return 0;
    }
    try {
      return Integer.parseInt(parts[index]);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static int versionQualifierRank(String version) {
    String normalized = defaultString(version);
    return normalized.contains("-") ? 0 : 1;
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

  private record CachedVersions(
      List<String> versions,
      long expiresAtMs
  ) {}
}
