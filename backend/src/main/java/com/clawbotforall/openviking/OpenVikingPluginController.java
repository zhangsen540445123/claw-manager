package com.clawbotforall.openviking;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenVikingPluginController {

  private final InstanceCommandService commandService;
  private final OpenVikingPluginService pluginService;

  public OpenVikingPluginController(
      InstanceCommandService commandService,
      OpenVikingPluginService pluginService
  ) {
    this.commandService = commandService;
    this.pluginService = pluginService;
  }

  @GetMapping("/api/admin/instances/{instanceId}/openviking-plugin")
  public Map<String, Object> status(
      @PathVariable String instanceId,
      @RequestParam(defaultValue = "false") boolean checkLatest,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.status(instance, checkLatest));
  }

  @GetMapping("/api/admin/openviking-plugins/versions")
  public Map<String, Object> versions(
      @RequestParam(defaultValue = "false") boolean forceRefresh,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("versions", pluginService.versions(forceRefresh));
  }

  @PostMapping("/api/admin/instances/{instanceId}/openviking-plugin/install")
  public Map<String, Object> install(
      @PathVariable String instanceId,
      @RequestBody(required = false) OpenVikingPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startInstall(instance, request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/instances/{instanceId}/openviking-plugin/upgrade")
  public Map<String, Object> upgrade(
      @PathVariable String instanceId,
      @RequestBody(required = false) OpenVikingPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startUpgrade(instance, request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/instances/{instanceId}/openviking-plugin/reinstall")
  public Map<String, Object> reinstall(
      @PathVariable String instanceId,
      @RequestBody(required = false) OpenVikingPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startReinstall(instance, request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/instances/{instanceId}/openviking-plugin/uninstall")
  public Map<String, Object> uninstall(
      @PathVariable String instanceId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startUninstall(instance));
  }

  @PostMapping("/api/admin/openviking-plugins/check")
  public Map<String, Object> checkMany(
      @RequestBody(required = false) OpenVikingPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugins", checkBatch(request));
  }

  @PostMapping("/api/admin/openviking-plugins/install")
  public Map<String, Object> installMany(
      @RequestBody(required = false) OpenVikingPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (id, instance) -> pluginService.startInstall(instance, version)));
  }

  @PostMapping("/api/admin/openviking-plugins/upgrade")
  public Map<String, Object> upgradeMany(
      @RequestBody(required = false) OpenVikingPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (id, instance) -> pluginService.startUpgrade(instance, version)));
  }

  @PostMapping("/api/admin/openviking-plugins/reinstall")
  public Map<String, Object> reinstallMany(
      @RequestBody(required = false) OpenVikingPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (id, instance) -> pluginService.startReinstall(instance, version)));
  }

  @PostMapping("/api/admin/openviking-plugins/uninstall")
  public Map<String, Object> uninstallMany(
      @RequestBody(required = false) OpenVikingPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugins", batch(request, (id, instance) -> pluginService.startUninstall(instance)));
  }

  private List<OpenVikingPluginBatchItem> batch(
      OpenVikingPluginBatchRequest request,
      BiFunction<String, InstanceEntity, PublicOpenVikingPluginStatus> action
  ) {
    List<OpenVikingPluginBatchItem> plugins = new ArrayList<>();
    for (String instanceId : requestedInstanceIds(request)) {
      try {
        InstanceEntity instance = commandService.requireInstance(instanceId);
        plugins.add(new OpenVikingPluginBatchItem(instanceId, action.apply(instanceId, instance)));
      } catch (RuntimeException error) {
        plugins.add(new OpenVikingPluginBatchItem(instanceId, failedStatus(error)));
      }
    }
    return plugins;
  }

  private List<OpenVikingPluginBatchItem> checkBatch(OpenVikingPluginBatchRequest request) {
    List<String> instanceIds = requestedInstanceIds(request);
    Map<String, InstanceEntity> instancesById = commandService.listInstancesByIds(instanceIds).stream()
        .collect(Collectors.toMap(InstanceEntity::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    List<OpenVikingPluginBatchItem> plugins = new ArrayList<>();
    for (String instanceId : instanceIds) {
      try {
        InstanceEntity instance = instancesById.get(instanceId);
        if (instance == null) {
          throw new ApiException(HttpStatus.NOT_FOUND, "实例不存在。");
        }
        plugins.add(new OpenVikingPluginBatchItem(instanceId, pluginService.status(instance, false)));
      } catch (RuntimeException error) {
        plugins.add(new OpenVikingPluginBatchItem(instanceId, failedStatus(error)));
      }
    }
    return plugins;
  }

  private static List<String> requestedInstanceIds(OpenVikingPluginBatchRequest request) {
    List<String> ids = new ArrayList<>();
    for (String instanceId : request == null || request.instanceIds() == null ? List.<String>of() : request.instanceIds()) {
      String normalized = instanceId == null ? "" : instanceId.trim();
      if (!normalized.isBlank() && !ids.contains(normalized)) {
        ids.add(normalized);
      }
    }
    return ids;
  }

  private static PublicOpenVikingPluginStatus failedStatus(RuntimeException error) {
    String message = error.getMessage() == null || error.getMessage().isBlank()
        ? "OpenViking 插件操作失败。"
        : error.getMessage();
    return new PublicOpenVikingPluginStatus(false, "", "", false, "failed", message, "", Instant.now().toString());
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }

  public record OpenVikingPluginVersionRequest(String version) {}

  public record OpenVikingPluginBatchRequest(List<String> instanceIds, String version) {}

  public record OpenVikingPluginBatchItem(String instanceId, PublicOpenVikingPluginStatus plugin) {}
}
