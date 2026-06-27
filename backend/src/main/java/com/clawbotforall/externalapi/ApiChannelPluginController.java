package com.clawbotforall.externalapi;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiChannelPluginController {
  private final InstanceCommandService commandService;
  private final ApiChannelPluginService pluginService;

  public ApiChannelPluginController(InstanceCommandService commandService, ApiChannelPluginService pluginService) {
    this.commandService = commandService;
    this.pluginService = pluginService;
  }

  @GetMapping("/api/admin/instances/{instanceId}/api-channel-plugin")
  public Map<String, Object> status(
      @PathVariable String instanceId,
      @RequestParam(defaultValue = "false") boolean checkLatest,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.status(instance, checkLatest));
  }

  @GetMapping("/api/admin/api-channel-plugins/versions")
  public Map<String, Object> versions(
      @RequestParam(defaultValue = "false") boolean forceRefresh,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("versions", pluginService.versions(forceRefresh));
  }

  @PostMapping("/api/admin/instances/{instanceId}/api-channel-plugin/install")
  public Map<String, Object> install(
      @PathVariable String instanceId,
      @RequestBody(required = false) ApiPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugin", pluginService.startInstall(commandService.requireInstance(instanceId), request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/instances/{instanceId}/api-channel-plugin/upgrade")
  public Map<String, Object> upgrade(
      @PathVariable String instanceId,
      @RequestBody(required = false) ApiPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugin", pluginService.startUpgrade(commandService.requireInstance(instanceId), request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/instances/{instanceId}/api-channel-plugin/reinstall")
  public Map<String, Object> reinstall(
      @PathVariable String instanceId,
      @RequestBody(required = false) ApiPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugin", pluginService.startReinstall(commandService.requireInstance(instanceId), request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/instances/{instanceId}/api-channel-plugin/uninstall")
  public Map<String, Object> uninstall(@PathVariable String instanceId, Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("plugin", pluginService.startUninstall(commandService.requireInstance(instanceId)));
  }

  @PostMapping("/api/admin/api-channel-plugins/check")
  public Map<String, Object> checkMany(
      @RequestBody(required = false) ApiPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    List<ApiPluginBatchItem> plugins = new ArrayList<>();
    for (String instanceId : requestedInstanceIds(request)) {
      try {
        InstanceEntity instance = commandService.requireInstance(instanceId);
        plugins.add(new ApiPluginBatchItem(instanceId, pluginService.status(instance, false)));
      } catch (RuntimeException error) {
        plugins.add(new ApiPluginBatchItem(instanceId, failedStatus(error)));
      }
    }
    return Map.of("plugins", plugins);
  }

  @PostMapping("/api/admin/api-channel-plugins/install")
  public Map<String, Object> installMany(@RequestBody(required = false) ApiPluginBatchRequest request, Authentication authentication) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (id, instance) -> pluginService.startInstall(instance, version)));
  }

  @PostMapping("/api/admin/api-channel-plugins/upgrade")
  public Map<String, Object> upgradeMany(@RequestBody(required = false) ApiPluginBatchRequest request, Authentication authentication) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (id, instance) -> pluginService.startUpgrade(instance, version)));
  }

  @PostMapping("/api/admin/api-channel-plugins/reinstall")
  public Map<String, Object> reinstallMany(@RequestBody(required = false) ApiPluginBatchRequest request, Authentication authentication) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (id, instance) -> pluginService.startReinstall(instance, version)));
  }

  @PostMapping("/api/admin/api-channel-plugins/uninstall")
  public Map<String, Object> uninstallMany(@RequestBody(required = false) ApiPluginBatchRequest request, Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("plugins", batch(request, (id, instance) -> pluginService.startUninstall(instance)));
  }

  private List<ApiPluginBatchItem> batch(
      ApiPluginBatchRequest request,
      BiFunction<String, InstanceEntity, PublicApiChannelPluginStatus> action
  ) {
    List<ApiPluginBatchItem> plugins = new ArrayList<>();
    for (String instanceId : requestedInstanceIds(request)) {
      try {
        InstanceEntity instance = commandService.requireInstance(instanceId);
        plugins.add(new ApiPluginBatchItem(instanceId, action.apply(instanceId, instance)));
      } catch (RuntimeException error) {
        plugins.add(new ApiPluginBatchItem(instanceId, failedStatus(error)));
      }
    }
    return plugins;
  }

  private static List<String> requestedInstanceIds(ApiPluginBatchRequest request) {
    List<String> ids = new ArrayList<>();
    for (String instanceId : request == null || request.instanceIds() == null ? List.<String>of() : request.instanceIds()) {
      String normalized = instanceId == null ? "" : instanceId.trim();
      if (!normalized.isBlank() && !ids.contains(normalized)) {
        ids.add(normalized);
      }
    }
    return ids;
  }

  private static PublicApiChannelPluginStatus failedStatus(RuntimeException error) {
    String message = error.getMessage() == null || error.getMessage().isBlank()
        ? "API Channel 插件操作失败。"
        : error.getMessage();
    return new PublicApiChannelPluginStatus(false, "", "", false, "failed", message, "", Instant.now().toString());
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }

  public record ApiPluginVersionRequest(String version) {}

  public record ApiPluginBatchRequest(List<String> instanceIds, String version) {}

  public record ApiPluginBatchItem(String instanceId, PublicApiChannelPluginStatus plugin) {}
}
