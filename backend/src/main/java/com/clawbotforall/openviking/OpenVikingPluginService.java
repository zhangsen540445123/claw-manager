package com.clawbotforall.openviking;

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
public class OpenVikingPluginService {

  private static final Logger log = LoggerFactory.getLogger(OpenVikingPluginService.class);
  private static final String PLUGIN_ID = "openviking";
  private static final String PACKAGE_NAME = "@claw-manager/openviking-openclaw-plugin";
  private static final String NPM_SPEC = "npm:" + PACKAGE_NAME;
  private static final String NPM_PROJECT_PREFIX = "claw-manager-openviking-openclaw-plugin";
  private static final String REGISTRY_URL = "https://registry.npmjs.org/%40claw-manager%2Fopenviking-openclaw-plugin";
  private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+(?:\\.\\d+){1,3}(?:[-+][0-9A-Za-z.-]+)?");
  private static final long TASK_TIMEOUT_MS = 10 * 60 * 1000L;
  private static final long VERSION_CACHE_TTL_MS = 60 * 60 * 1000L;

  private final OpenClawRuntime openClawRuntime;
  private final InstanceCommandService commandService;
  private final InstanceFileService fileService;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final OpenVikingSettingsService settingsService;
  private final PluginOperationCoordinator operationCoordinator;
  private final Executor executor;
  private final Supplier<List<String>> versionSupplier;
  private final ConcurrentMap<String, PublicOpenVikingPluginStatus> taskStatuses = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> taskJobs = new ConcurrentHashMap<>();
  private final AtomicReference<CachedVersions> cachedVersions = new AtomicReference<>();

  @Autowired
  public OpenVikingPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      OpenVikingSettingsService settingsService,
      PluginOperationCoordinator operationCoordinator
  ) {
    this(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        objectMapper,
        settingsService,
        Executors.newCachedThreadPool(task -> {
          Thread thread = new Thread(task, "openviking-plugin-" + System.nanoTime());
          thread.setDaemon(true);
          return thread;
        }),
        () -> fetchVersionsFromNpm(objectMapper),
        operationCoordinator
    );
  }

  OpenVikingPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      OpenVikingSettingsService settingsService,
      Executor executor,
      Supplier<List<String>> versionSupplier
  ) {
    this(
        openClawRuntime,
        commandService,
        fileService,
        mutationMapper,
        eventPublisher,
        objectMapper,
        settingsService,
        executor,
        versionSupplier,
        new PluginOperationCoordinator()
    );
  }

  OpenVikingPluginService(
      OpenClawRuntime openClawRuntime,
      InstanceCommandService commandService,
      InstanceFileService fileService,
      InstanceMutationMapper mutationMapper,
      InstanceEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      OpenVikingSettingsService settingsService,
      Executor executor,
      Supplier<List<String>> versionSupplier,
      PluginOperationCoordinator operationCoordinator
  ) {
    this.openClawRuntime = openClawRuntime;
    this.commandService = commandService;
    this.fileService = fileService;
    this.mutationMapper = mutationMapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.settingsService = settingsService;
    this.operationCoordinator = operationCoordinator;
    this.executor = executor;
    this.versionSupplier = versionSupplier;
  }

  public PublicOpenVikingPluginStatus status(InstanceEntity instance, boolean checkLatest) {
    PublicOpenVikingPluginStatus running = taskStatuses.get(instance.getId());
    if (running != null && isRunning(running.status())) {
      return running;
    }
    String currentVersion = currentVersion(instance);
    boolean installed = !currentVersion.isBlank();
    String latest = installed && checkLatest ? cachedLatestVersion() : "";
    boolean upgradable = installed && !latest.isBlank() && compareVersion(latest, currentVersion) > 0;
    return new PublicOpenVikingPluginStatus(
        installed,
        currentVersion,
        latest,
        upgradable,
        installed ? "installed" : "missing",
        installed ? "OpenViking 插件已安装。" : "OpenViking 插件尚未安装。",
        "",
        Instant.now().toString()
    );
  }

  public OpenVikingPluginVersions versions() {
    return versions(false);
  }

  public OpenVikingPluginVersions versions(boolean forceRefresh) {
    CachedVersions cached = cachedVersions.get();
    if (!forceRefresh && cached != null && Instant.now().toEpochMilli() - cached.loadedAtMs() < VERSION_CACHE_TTL_MS) {
      return publicVersions(cached.versions());
    }
    try {
      List<String> versions = versionSupplier.get();
      cachedVersions.set(new CachedVersions(versions, Instant.now().toEpochMilli()));
      return publicVersions(versions);
    } catch (RuntimeException error) {
      log.warn("OpenViking 插件版本读取失败：{}", message(error));
      return cached == null ? publicVersions(List.of()) : publicVersions(cached.versions());
    }
  }

  public PublicOpenVikingPluginStatus startInstall(InstanceEntity instance, String version) {
    String targetVersion = resolveInstallVersion(version);
    OpenVikingEffectiveSettings settings = requireInstallableSettings();
    requireRunning(instance);
    return startTask(
        instance,
        "installing",
        "OpenViking 插件安装已开始。",
        "OpenViking 插件安装失败：",
        () -> {
          runCommand(instance, installCommand(settings, targetVersion));
          runCommand(instance, setupCommand(settings));
          enableOpenVikingPlugin(instance, settings);
          return successStatus(instance, "OpenViking 插件安装完成。如 Gateway 未加载该插件，请重启 Gateway。", "");
        }
    );
  }

  public PublicOpenVikingPluginStatus startUpgrade(InstanceEntity instance, String version) {
    String targetVersion = resolveInstallVersion(version);
    OpenVikingEffectiveSettings settings = requireInstallableSettings();
    requireRunning(instance);
    return startTask(
        instance,
        "upgrading",
        "OpenViking 插件升级已开始。",
        "OpenViking 插件升级失败：",
        () -> {
          runCommand(instance, installCommand(settings, targetVersion));
          runCommand(instance, setupCommand(settings));
          enableOpenVikingPlugin(instance, settings);
          return successStatus(instance, "OpenViking 插件升级完成。如 Gateway 未加载该插件，请重启 Gateway。", "");
        }
    );
  }

  public PublicOpenVikingPluginStatus startReinstall(InstanceEntity instance, String version) {
    String targetVersion = resolveInstallVersion(version);
    OpenVikingEffectiveSettings settings = requireInstallableSettings();
    requireRunning(instance);
    return startTask(
        instance,
        "reinstalling",
        "OpenViking 插件重新安装已开始。",
        "OpenViking 插件重新安装失败：",
        () -> {
          runCommand(instance, installCommand(settings, targetVersion));
          runCommand(instance, setupCommand(settings));
          enableOpenVikingPlugin(instance, settings);
          return successStatus(instance, "OpenViking 插件重新安装完成。如 Gateway 未加载该插件，请重启 Gateway。", "");
        }
    );
  }

  public PublicOpenVikingPluginStatus startUninstall(InstanceEntity instance) {
    requireRunning(instance);
    return startTask(
        instance,
        "uninstalling",
        "OpenViking 插件卸载已开始。",
        "OpenViking 插件卸载失败：",
        () -> {
          runCommand(instance, List.of("openclaw", "plugins", "uninstall", PLUGIN_ID, "--force"));
          disableOpenVikingPlugin(instance);
          return new PublicOpenVikingPluginStatus(false, "", "", false, "missing", "OpenViking 插件已卸载。", "", Instant.now().toString());
        }
    );
  }

  private PublicOpenVikingPluginStatus startTask(
      InstanceEntity instance,
      String runningStatus,
      String startedMessage,
      String failurePrefix,
      TaskBody body
  ) {
    PublicOpenVikingPluginStatus existing = taskStatuses.get(instance.getId());
    if (existing != null && isRunning(existing.status())) {
      return existing;
    }
    if (taskJobs.putIfAbsent(instance.getId(), true) != null) {
      return runningStatus(runningStatus, startedMessage);
    }
    if (!operationCoordinator.tryStart(instance.getId(), "OpenViking 插件")) {
      taskJobs.remove(instance.getId());
      throw new ApiException(HttpStatus.CONFLICT, operationCoordinator.currentOwner(instance.getId()) + "正在执行，请等待当前插件任务完成后再操作。");
    }
    PublicOpenVikingPluginStatus started = runningStatus(runningStatus, startedMessage);
    taskStatuses.put(instance.getId(), started);
    publish(instance, started);
    try {
      executor.execute(() -> {
      try {
        PublicOpenVikingPluginStatus completed = body.run();
        taskStatuses.put(instance.getId(), completed);
        publish(instance, completed);
      } catch (RuntimeException error) {
        PublicOpenVikingPluginStatus failed = failedStatus(failurePrefix + message(error));
        taskStatuses.put(instance.getId(), failed);
        publish(instance, failed);
      } finally {
        taskJobs.remove(instance.getId());
        operationCoordinator.finish(instance.getId(), "OpenViking 插件");
      }
      });
    } catch (RuntimeException error) {
      taskJobs.remove(instance.getId());
      operationCoordinator.finish(instance.getId(), "OpenViking 插件");
      throw error;
    }
    return started;
  }

  private void runCommand(InstanceEntity instance, List<String> command) {
    CountDownLatch done = new CountDownLatch(1);
    StringBuilder output = new StringBuilder();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    openClawRuntime.startExec(instance, command, TASK_TIMEOUT_MS, Map.of(), new RuntimeExecListener() {
      @Override
      public void onOutput(String text) {
        output.append(text);
      }

      @Override
      public void onComplete(int exitCode) {
        if (exitCode != 0) {
          failure.set(new IllegalStateException("命令退出码 " + exitCode + "：" + tail(output.toString())));
        }
        done.countDown();
      }

      @Override
      public void onTimeout() {
        failure.set(new IllegalStateException("命令执行超时：" + String.join(" ", command)));
        done.countDown();
      }

      @Override
      public void onError(Throwable error) {
        failure.set(new IllegalStateException(message(error), error));
        done.countDown();
      }
    });
    try {
      if (!done.await(TASK_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("命令执行超时：" + String.join(" ", command));
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("命令执行被中断。", error);
    }
    if (failure.get() != null) {
      throw failure.get();
    }
  }

  private void enableOpenVikingPlugin(InstanceEntity instance, OpenVikingEffectiveSettings settings) {
    List<Object> allow = new ArrayList<>(readJsonList(instance.getPluginsAllow()));
    if (!allow.contains(PLUGIN_ID)) {
      allow.add(PLUGIN_ID);
    }
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("mode", "remote");
    config.put("baseUrl", settings.baseUrl());
    config.put("accountId", settings.accountId());
    config.put("identityHashSecret", "${OPENVIKING_IDENTITY_HASH_SECRET}");
    config.put("peer_role", "assistant");
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("enabled", true);
    entry.put("config", config);
    Map<String, Object> entries = new LinkedHashMap<>(readJsonMap(instance.getPluginsEntries()));
    entries.put(PLUGIN_ID, entry);
    updatePluginColumns(instance, allow, entries);
  }

  private void disableOpenVikingPlugin(InstanceEntity instance) {
    List<Object> allow = new ArrayList<>(readJsonList(instance.getPluginsAllow()));
    allow.removeIf(item -> PLUGIN_ID.equals(String.valueOf(item)));
    Map<String, Object> entries = new LinkedHashMap<>(readJsonMap(instance.getPluginsEntries()));
    entries.remove(PLUGIN_ID);
    updatePluginColumns(instance, allow, entries);
  }

  private void updatePluginColumns(InstanceEntity instance, List<Object> allow, Map<String, Object> entries) {
    String now = Instant.now().toString();
    String allowJson = writeJson(allow);
    String entriesJson = writeJson(entries);
    mutationMapper.updateInstancePlugins(instance.getId(), allowJson, entriesJson, now);
    instance.setPluginsAllow(allowJson);
    instance.setPluginsEntries(entriesJson);
    instance.setUpdatedAt(now);
    fileService.writeInstanceFiles(instance, commandService.listModels(instance.getId()));
  }

  private OpenVikingEffectiveSettings requireInstallableSettings() {
    OpenVikingEffectiveSettings settings = settingsService.effectiveSettings();
    if (settings.baseUrl() == null || settings.baseUrl().isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking Base URL 不能为空，请先完成 OpenViking 配置预置。");
    }
    if (settings.accountId() == null || settings.accountId().isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking Account ID 不能为空。");
    }
    if (settings.identityHashSecret() == null || settings.identityHashSecret().isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking Identity Hash Secret 不可用。");
    }
    if (settings.rootApiKey() == null || settings.rootApiKey().isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking Root API Key 不能为空，请先完成 OpenViking 配置预置。");
    }
    if (settings.brokerToken() == null || settings.brokerToken().isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking broker token 不可用。");
    }
    return settings;
  }

  private void requireRunning(InstanceEntity instance) {
    RuntimeState state = openClawRuntime.inspectInstance(instance);
    if (state == null || !state.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "实例未运行，无法管理 OpenViking 插件。");
    }
  }

  private List<String> installCommand(OpenVikingEffectiveSettings settings, String version) {
    String baseSpec = normalizeNpmSpec(settings.pluginPackage());
    String spec = version == null || version.isBlank() ? baseSpec : stripNpmVersion(baseSpec) + "@" + version;
    return List.of("openclaw", "plugins", "install", spec, "--force");
  }

  private String normalizeNpmSpec(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) {
      return NPM_SPEC;
    }
    if (normalized.startsWith("npm:") || normalized.startsWith("file:")) {
      return normalized;
    }
    return "npm:" + normalized;
  }

  private String stripNpmVersion(String spec) {
    if (spec == null || spec.isBlank() || spec.startsWith("file:")) {
      return spec == null || spec.isBlank() ? NPM_SPEC : spec;
    }
    String prefix = spec.startsWith("npm:") ? "npm:" : "";
    String packageSpec = prefix.isBlank() ? spec : spec.substring(prefix.length());
    int slashIndex = packageSpec.indexOf('/');
    int atIndex = packageSpec.lastIndexOf('@');
    if (atIndex > Math.max(0, slashIndex)) {
      packageSpec = packageSpec.substring(0, atIndex);
    }
    return prefix + packageSpec;
  }

  private List<String> setupCommand(OpenVikingEffectiveSettings settings) {
    return List.of(
        "openclaw",
        "openviking",
        "setup",
        "--base-url",
        settings.baseUrl(),
        "--account-id",
        settings.accountId(),
        "--allow-offline",
        "--force-slot",
        "--json"
    );
  }

  private String resolveInstallVersion(String version) {
    String normalized = version == null ? "" : version.trim();
    if (normalized.isBlank() || "latest".equalsIgnoreCase(normalized)) {
      return versions().latest();
    }
    if (!VERSION_PATTERN.matcher(normalized).matches() || !versions().versions().contains(normalized)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "只能选择已发布的 OpenViking 插件版本。");
    }
    return normalized;
  }

  private PublicOpenVikingPluginStatus successStatus(InstanceEntity instance, String message, String output) {
    PublicOpenVikingPluginStatus detected = status(instance, false);
    String version = detected.currentVersion();
    if (version.isBlank()) {
      version = versions().latest();
    }
    return new PublicOpenVikingPluginStatus(true, version, "", false, "installed", message, output, Instant.now().toString());
  }

  private String currentVersion(InstanceEntity instance) {
    try {
      InstancePaths paths = fileService.paths(instance.getId());
      Path legacy = paths.homeDir().resolve(".openclaw").resolve("extensions").resolve(PLUGIN_ID).resolve("package.json");
      if (Files.isRegularFile(legacy)) {
        return readVersionFromPackage(legacy);
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
            .filter(version -> !version.isBlank())
            .findFirst()
            .orElse("");
      }
    } catch (RuntimeException | IOException error) {
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
    String normalized = value == null ? "" : value.trim();
    if (normalized.startsWith("npm:")) {
      normalized = normalized.substring("npm:".length());
    }
    int index = normalized.lastIndexOf('@');
    return index >= 0 && index + 1 < normalized.length() ? normalized.substring(index + 1) : normalized;
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
      throw new IllegalStateException("OpenViking 插件配置 JSON 写入失败。", error);
    }
  }

  private OpenVikingPluginVersions publicVersions(List<String> versions) {
    List<String> normalized = versions == null ? List.of() : versions.stream().filter(v -> v != null && !v.isBlank()).limit(5).toList();
    return new OpenVikingPluginVersions(normalized.isEmpty() ? "" : normalized.getFirst(), normalized);
  }

  private String cachedLatestVersion() {
    return versions().latest();
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
      versions.fieldNames().forEachRemaining(version -> {
        if (VERSION_PATTERN.matcher(version).matches()) {
          result.add(version);
        }
      });
      result.sort(OpenVikingPluginService::compareVersionDesc);
      return result;
    } catch (Exception error) {
      throw new IllegalStateException("OpenViking 插件 npm 版本读取失败。", error);
    }
  }

  private static int compareVersionDesc(String left, String right) {
    return -compareVersion(left, right);
  }

  private static int compareVersion(String left, String right) {
    String[] a = left.split("[.-]");
    String[] b = right.split("[.-]");
    int max = Math.max(a.length, b.length);
    for (int i = 0; i < max; i += 1) {
      int ai = i < a.length ? parseInt(a[i]) : 0;
      int bi = i < b.length ? parseInt(b[i]) : 0;
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
    return "installing".equals(status) || "upgrading".equals(status) || "reinstalling".equals(status) || "uninstalling".equals(status);
  }

  private PublicOpenVikingPluginStatus runningStatus(String status, String message) {
    return new PublicOpenVikingPluginStatus(false, "", "", false, status, message, "", Instant.now().toString());
  }

  private PublicOpenVikingPluginStatus failedStatus(String message) {
    return new PublicOpenVikingPluginStatus(false, "", "", false, "failed", message, "", Instant.now().toString());
  }

  private void publish(InstanceEntity instance, PublicOpenVikingPluginStatus status) {
    eventPublisher.publishOpenVikingPluginUpdated(instance.getId(), status);
  }

  private static String message(Throwable error) {
    return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
  }

  private static String tail(String value) {
    if (value == null || value.length() <= 4000) {
      return value == null ? "" : value;
    }
    return value.substring(value.length() - 4000);
  }

  private record CachedVersions(List<String> versions, long loadedAtMs) {}

  private interface TaskBody {
    PublicOpenVikingPluginStatus run();
  }
}
