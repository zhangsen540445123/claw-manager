package com.clawbotforall.miniapp;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginBatchItem;
import com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginBatchRequest;
import com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginVersionRequest;
import com.clawbotforall.externalapi.PublicApiChannelPluginStatus;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MiniappBridgePluginController {
  private final InstanceCommandService instances;
  private final MiniappBridgePluginService plugins;

  public MiniappBridgePluginController(InstanceCommandService instances, MiniappBridgePluginService plugins) {
    this.instances = instances;
    this.plugins = plugins;
  }

  @GetMapping("/api/admin/instances/{id}/miniapp-bridge-plugin")
  public Map<String, Object> status(
      @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean checkLatest,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugin", plugins.status(instances.requireInstance(id), checkLatest));
  }

  @GetMapping("/api/admin/miniapp-bridge-plugins/versions")
  public Map<String, Object> versions(
      @RequestParam(defaultValue = "false") boolean forceRefresh,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("versions", plugins.versions(forceRefresh));
  }

  @PostMapping("/api/admin/miniapp-bridge-plugins/check")
  public Map<String, Object> check(
      @RequestBody(required = false) ApiPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    List<ApiPluginBatchItem> result = new ArrayList<>();
    for (String id : requestedInstanceIds(request)) {
      try {
        result.add(new ApiPluginBatchItem(id, plugins.status(instances.requireInstance(id), false)));
      } catch (RuntimeException error) {
        result.add(new ApiPluginBatchItem(id, failedStatus(error)));
      }
    }
    return Map.of("plugins", result);
  }

  @PostMapping("/api/admin/instances/{id}/miniapp-bridge-plugin/{operation}")
  public Map<String, Object> operate(
      @PathVariable String id,
      @PathVariable String operation,
      @RequestBody(required = false) ApiPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    requireOperation(operation);
    InstanceEntity instance = instances.requireInstance(id);
    return Map.of("plugin", operate(instance, operation, request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/miniapp-bridge-plugins/{operation}")
  public Map<String, Object> batch(
      @PathVariable String operation,
      @RequestBody(required = false) ApiPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    requireOperation(operation);
    String version = request == null ? "" : request.version();
    List<ApiPluginBatchItem> result = new ArrayList<>();
    for (String id : requestedInstanceIds(request)) {
      try {
        result.add(new ApiPluginBatchItem(id, operate(instances.requireInstance(id), operation, version)));
      } catch (RuntimeException error) {
        result.add(new ApiPluginBatchItem(id, failedStatus(error)));
      }
    }
    return Map.of("plugins", result);
  }

  private PublicApiChannelPluginStatus operate(InstanceEntity instance, String operation, String version) {
    return switch (operation) {
      case "install" -> plugins.startInstall(instance, version);
      case "upgrade" -> plugins.startUpgrade(instance, version);
      case "reinstall" -> plugins.startReinstall(instance, version);
      case "uninstall" -> plugins.startUninstall(instance);
      default -> throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的插件操作。");
    };
  }

  private static List<String> requestedInstanceIds(ApiPluginBatchRequest request) {
    List<String> ids = new ArrayList<>();
    for (String instanceId : request == null || request.instanceIds() == null
        ? List.<String>of()
        : request.instanceIds()) {
      String normalized = instanceId == null ? "" : instanceId.trim();
      if (!normalized.isBlank() && !ids.contains(normalized)) {
        ids.add(normalized);
      }
    }
    return ids;
  }

  private static PublicApiChannelPluginStatus failedStatus(RuntimeException error) {
    String message = error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName()
        : error.getMessage();
    return new PublicApiChannelPluginStatus(
        false, "", "", false, "failed", message, "", Instant.now().toString());
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }

  private static void requireOperation(String operation) {
    if (!List.of("install", "upgrade", "reinstall", "uninstall").contains(operation)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的插件操作。");
    }
  }
}
