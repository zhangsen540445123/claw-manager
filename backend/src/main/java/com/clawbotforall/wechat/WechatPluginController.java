package com.clawbotforall.wechat;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理 OpenClaw 实例内的微信插件。
 */
@RestController
public class WechatPluginController {

  private static final Logger log = LoggerFactory.getLogger(WechatPluginController.class);

  private final InstanceCommandService commandService;
  private final WechatPluginService pluginService;

  public WechatPluginController(
      InstanceCommandService commandService,
      WechatPluginService pluginService
  ) {
    this.commandService = commandService;
    this.pluginService = pluginService;
  }

  @GetMapping("/api/admin/instances/{instanceId}/wechat-plugin")
  public Map<String, Object> status(
      @PathVariable String instanceId,
      @RequestParam(defaultValue = "false") boolean checkLatest,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.status(instance, checkLatest));
  }

  @GetMapping("/api/admin/wechat-plugins/versions")
  public Map<String, Object> versions(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("versions", pluginService.versions());
  }

  @PostMapping("/api/admin/instances/{instanceId}/wechat-plugin/install")
  public Map<String, Object> install(
      @PathVariable String instanceId,
      @RequestBody(required = false) WechatPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startInstall(instance, request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/instances/{instanceId}/wechat-plugin/uninstall")
  public Map<String, Object> uninstall(
      @PathVariable String instanceId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startUninstall(instance));
  }

  @PostMapping("/api/admin/instances/{instanceId}/wechat-plugin/upgrade")
  public Map<String, Object> upgrade(
      @PathVariable String instanceId,
      @RequestBody(required = false) WechatPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startUpgrade(instance, request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/instances/{instanceId}/wechat-plugin/reinstall")
  public Map<String, Object> reinstall(
      @PathVariable String instanceId,
      @RequestBody(required = false) WechatPluginVersionRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startReinstall(instance, request == null ? "" : request.version()));
  }

  @PostMapping("/api/admin/wechat-plugins/check")
  public Map<String, Object> checkMany(
      @RequestBody(required = false) WechatPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugins", checkBatch(request));
  }

  @PostMapping("/api/admin/wechat-plugins/install")
  public Map<String, Object> installMany(
      @RequestBody(required = false) WechatPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (instanceId, instance) -> pluginService.startInstall(instance, version)));
  }

  @PostMapping("/api/admin/wechat-plugins/uninstall")
  public Map<String, Object> uninstallMany(
      @RequestBody(required = false) WechatPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugins", batch(request, (instanceId, instance) -> pluginService.startUninstall(instance)));
  }

  @PostMapping("/api/admin/wechat-plugins/upgrade")
  public Map<String, Object> upgradeMany(
      @RequestBody(required = false) WechatPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (instanceId, instance) -> pluginService.startUpgrade(instance, version)));
  }

  @PostMapping("/api/admin/wechat-plugins/reinstall")
  public Map<String, Object> reinstallMany(
      @RequestBody(required = false) WechatPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    String version = request == null ? "" : request.version();
    return Map.of("plugins", batch(request, (instanceId, instance) -> pluginService.startReinstall(instance, version)));
  }

  private List<WechatPluginBatchItem> batch(
      WechatPluginBatchRequest request,
      BiFunction<String, InstanceEntity, PublicWechatPluginStatus> action
  ) {
    List<WechatPluginBatchItem> plugins = new ArrayList<>();
    for (String instanceId : request == null || request.instanceIds() == null ? List.<String>of() : request.instanceIds()) {
      if (instanceId == null || instanceId.isBlank()) {
        continue;
      }
      try {
        InstanceEntity instance = commandService.requireInstance(instanceId);
        plugins.add(new WechatPluginBatchItem(instanceId, action.apply(instanceId, instance)));
      } catch (RuntimeException error) {
        plugins.add(new WechatPluginBatchItem(instanceId, failedStatus(error)));
      }
    }
    return plugins;
  }

  private List<WechatPluginBatchItem> checkBatch(WechatPluginBatchRequest request) {
    long startedAt = System.nanoTime();
    List<String> instanceIds = requestedInstanceIds(request);
    Map<String, InstanceEntity> instancesById = commandService.listInstancesByIds(instanceIds).stream()
        .collect(Collectors.toMap(InstanceEntity::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    List<WechatPluginBatchItem> plugins = new ArrayList<>();
    for (String instanceId : instanceIds) {
      try {
        InstanceEntity instance = instancesById.get(instanceId);
        if (instance == null) {
          throw new ApiException(HttpStatus.NOT_FOUND, "实例不存在。");
        }
        plugins.add(new WechatPluginBatchItem(instanceId, pluginService.status(instance, false)));
      } catch (RuntimeException error) {
        plugins.add(new WechatPluginBatchItem(instanceId, failedStatus(error)));
      }
    }
    log.info(
        "微信插件批量本地检测完成：requested={}, returned={}, elapsedMs={}",
        instanceIds.size(),
        plugins.size(),
        (System.nanoTime() - startedAt) / 1_000_000L
    );
    return plugins;
  }

  private static List<String> requestedInstanceIds(WechatPluginBatchRequest request) {
    List<String> instanceIds = new ArrayList<>();
    for (String instanceId : request == null || request.instanceIds() == null ? List.<String>of() : request.instanceIds()) {
      String normalized = instanceId == null ? "" : instanceId.trim();
      if (!normalized.isBlank() && !instanceIds.contains(normalized)) {
        instanceIds.add(normalized);
      }
    }
    return instanceIds;
  }

  private static PublicWechatPluginStatus failedStatus(RuntimeException error) {
    String message = error.getMessage() == null || error.getMessage().isBlank()
        ? "微信插件操作失败。"
        : error.getMessage();
    return new PublicWechatPluginStatus(
        false,
        "",
        "",
        false,
        "failed",
        message,
        "",
        Instant.now().toString()
    );
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }

  public record WechatPluginVersionRequest(String version) {}

  public record WechatPluginBatchRequest(List<String> instanceIds, String version) {}

  public record WechatPluginBatchItem(String instanceId, PublicWechatPluginStatus plugin) {}
}
