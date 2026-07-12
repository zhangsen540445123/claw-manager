package com.clawbotforall.miniapp;

import com.clawbotforall.externalapi.ApiChannelPluginService.ApiChannelPluginVersions;
import com.clawbotforall.externalapi.PublicApiChannelPluginStatus;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.plugin.PluginOperationCoordinator;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MiniappBridgePluginService {
  private static final String PLUGIN_ID = "miniapp-bridge";
  private static final String PACKAGE_NAME = "@claw-manager/miniapp-bridge-plugin";
  private static final String NPM_SPEC = "npm:" + PACKAGE_NAME;
  private static final String REGISTRY_URL = "https://registry.npmjs.org/%40claw-manager%2Fminiapp-bridge-plugin";
  private static final long TIMEOUT_MS = 600_000;

  private final OpenClawRuntime runtime;
  private final InstanceCommandService commandService;
  private final InstanceFileService fileService;
  private final InstanceMutationMapper mutationMapper;
  private final ObjectMapper objectMapper;
  private final PluginOperationCoordinator coordinator;
  private final Map<String, PublicApiChannelPluginStatus> tasks = new ConcurrentHashMap<>();

  public MiniappBridgePluginService(OpenClawRuntime runtime, InstanceCommandService commandService,
      InstanceFileService fileService, InstanceMutationMapper mutationMapper, ObjectMapper objectMapper,
      PluginOperationCoordinator coordinator) {
    this.runtime = runtime;
    this.commandService = commandService;
    this.fileService = fileService;
    this.mutationMapper = mutationMapper;
    this.objectMapper = objectMapper;
    this.coordinator = coordinator;
  }

  public PublicApiChannelPluginStatus status(InstanceEntity instance, boolean checkLatest) {
    PublicApiChannelPluginStatus task = tasks.get(instance.getId());
    if (task != null && task.status().endsWith("ing")) return task;
    String current = currentVersion(instance);
    String latest = checkLatest ? latestVersion() : "";
    boolean installed = !current.isBlank();
    return new PublicApiChannelPluginStatus(installed, current, latest,
        installed && !latest.isBlank() && !latest.equals(current), installed ? "installed" : "missing",
        installed ? "小程序 Bridge 插件已安装。" : "小程序 Bridge 插件尚未安装。", "", Instant.now().toString());
  }

  public ApiChannelPluginVersions versions() {
    String latest = latestVersion();
    return new ApiChannelPluginVersions(latest, latest.isBlank() ? List.of() : List.of(latest));
  }

  public PublicApiChannelPluginStatus install(InstanceEntity instance, String version, String operation) {
    if (!runtime.inspectInstance(instance).running()) throw new ApiException(HttpStatus.CONFLICT, "请先启动该 OpenClaw 实例。");
    String target = version == null || version.isBlank() ? latestVersion() : version.trim();
    if (target.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "未能获取小程序 Bridge 插件版本。");
    String state = operation + "ing";
    PublicApiChannelPluginStatus started = new PublicApiChannelPluginStatus(false, "", target, false, state,
        "小程序 Bridge 插件任务已开始。", "", Instant.now().toString());
    if (!coordinator.tryStart(instance.getId(), "小程序 Bridge 插件")) {
      throw new ApiException(HttpStatus.CONFLICT, coordinator.currentOwner(instance.getId()) + "正在执行。");
    }
    tasks.put(instance.getId(), started);
    Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        run(instance, List.of("openclaw", "plugins", "install", NPM_SPEC + "@" + target, "--force"));
        enable(instance);
        tasks.remove(instance.getId());
        tasks.put(instance.getId(), status(instance, false));
      } catch (RuntimeException error) {
        tasks.put(instance.getId(), new PublicApiChannelPluginStatus(false, "", target, false, "failed",
            "小程序 Bridge 插件操作失败：" + error.getMessage(), "", Instant.now().toString()));
      } finally {
        coordinator.finish(instance.getId(), "小程序 Bridge 插件");
      }
    });
    return started;
  }

  public PublicApiChannelPluginStatus uninstall(InstanceEntity instance) {
    PublicApiChannelPluginStatus started = new PublicApiChannelPluginStatus(true, currentVersion(instance), "", false,
        "uninstalling", "小程序 Bridge 插件卸载已开始。", "", Instant.now().toString());
    if (!coordinator.tryStart(instance.getId(), "小程序 Bridge 插件")) throw new ApiException(HttpStatus.CONFLICT, "已有插件任务正在执行。");
    tasks.put(instance.getId(), started);
    Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        run(instance, List.of("openclaw", "plugins", "uninstall", PLUGIN_ID, "--force"));
        disable(instance);
        tasks.put(instance.getId(), new PublicApiChannelPluginStatus(false, "", "", false, "missing", "小程序 Bridge 插件已卸载。", "", Instant.now().toString()));
      } catch (RuntimeException error) {
        tasks.put(instance.getId(), new PublicApiChannelPluginStatus(true, currentVersion(instance), "", false, "failed", error.getMessage(), "", Instant.now().toString()));
      } finally { coordinator.finish(instance.getId(), "小程序 Bridge 插件"); }
    });
    return started;
  }

  private void enable(InstanceEntity instance) { updateConfig(instance, true); }
  private void disable(InstanceEntity instance) { updateConfig(instance, false); }

  private void updateConfig(InstanceEntity instance, boolean enabled) {
    try {
      List<Object> allow = instance.getPluginsAllow() == null || instance.getPluginsAllow().isBlank()
          ? new ArrayList<>() : new ArrayList<>(objectMapper.readValue(instance.getPluginsAllow(), List.class));
      Map<String, Object> entries = instance.getPluginsEntries() == null || instance.getPluginsEntries().isBlank()
          ? new LinkedHashMap<>() : new LinkedHashMap<>(objectMapper.readValue(instance.getPluginsEntries(), Map.class));
      allow.removeIf(value -> PLUGIN_ID.equals(String.valueOf(value)));
      if (enabled) { allow.add(PLUGIN_ID); entries.put(PLUGIN_ID, Map.of("enabled", true)); } else entries.remove(PLUGIN_ID);
      String allowJson = objectMapper.writeValueAsString(allow);
      String entriesJson = objectMapper.writeValueAsString(entries);
      mutationMapper.updateInstancePlugins(instance.getId(), allowJson, entriesJson, Instant.now().toString());
      instance.setPluginsAllow(allowJson); instance.setPluginsEntries(entriesJson);
      fileService.writeInstanceFiles(instance, commandService.listModels(instance.getId()));
    } catch (Exception error) { throw new IllegalStateException("插件配置写入失败。", error); }
  }

  private String currentVersion(InstanceEntity instance) {
    Path openClawDir = fileService.paths(instance.getId()).homeDir().resolve(".openclaw");
    String npmVersion = currentNpmProjectVersion(openClawDir.resolve("npm").resolve("projects"));
    if (!npmVersion.isBlank()) return npmVersion;
    return packageVersion(openClawDir.resolve("extensions").resolve(PLUGIN_ID).resolve("package.json"));
  }

  private String currentNpmProjectVersion(Path projectsDir) {
    if (!Files.isDirectory(projectsDir)) return "";
    try (Stream<Path> projects = Files.find(projectsDir, 2,
        (path, attrs) -> attrs.isRegularFile() && "package.json".equals(path.getFileName().toString()))) {
      return projects.map(this::packageVersion).filter(value -> !value.isBlank()).findFirst().orElse("");
    } catch (IOException ignored) {
      return "";
    }
  }

  private String packageVersion(Path path) {
    try {
      JsonNode json = objectMapper.readTree(path.toFile());
      if (PACKAGE_NAME.equals(json.path("name").asText())) return json.path("version").asText();
      return json.path("dependencies").path(PACKAGE_NAME).asText("");
    }
    catch (Exception ignored) { return ""; }
  }

  private String latestVersion() {
    try {
      HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(REGISTRY_URL)).GET().build(), HttpResponse.BodyHandlers.ofString());
      return response.statusCode() / 100 == 2 ? objectMapper.readTree(response.body()).path("dist-tags").path("latest").asText("") : "";
    } catch (Exception ignored) { return ""; }
  }

  private void run(InstanceEntity instance, List<String> command) {
    CountDownLatch latch = new CountDownLatch(1); AtomicReference<Throwable> error = new AtomicReference<>(); AtomicReference<Integer> code = new AtomicReference<>();
    runtime.startExec(instance, command, TIMEOUT_MS, Map.of(), new RuntimeExecListener() {
      public void onOutput(String chunk) {}
      public void onComplete(int exitCode) { code.set(exitCode); latch.countDown(); }
      public void onTimeout() { error.set(new IllegalStateException("命令执行超时。")); latch.countDown(); }
      public void onError(Throwable value) { error.set(value); latch.countDown(); }
    });
    try { if (!latch.await(TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("命令执行超时。"); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("命令被中断。", interrupted); }
    if (error.get() != null) throw new IllegalStateException(error.get().getMessage(), error.get());
    if (code.get() == null || code.get() != 0) throw new IllegalStateException("插件命令退出码：" + code.get());
  }
}
