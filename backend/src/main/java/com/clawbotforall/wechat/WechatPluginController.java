package com.clawbotforall.wechat;

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

/**
 * 管理 OpenClaw 实例内的微信插件。
 */
@RestController
public class WechatPluginController {

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

  @PostMapping("/api/admin/instances/{instanceId}/wechat-plugin/install")
  public Map<String, Object> install(
      @PathVariable String instanceId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startInstall(instance));
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
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("plugin", pluginService.startUpgrade(instance));
  }

  @PostMapping("/api/admin/wechat-plugins/check")
  public Map<String, Object> checkMany(
      @RequestBody(required = false) WechatPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugins", batch(request, (instanceId, instance) -> pluginService.status(instance, true)));
  }

  @PostMapping("/api/admin/wechat-plugins/install")
  public Map<String, Object> installMany(
      @RequestBody(required = false) WechatPluginBatchRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("plugins", batch(request, (instanceId, instance) -> pluginService.startInstall(instance)));
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
    return Map.of("plugins", batch(request, (instanceId, instance) -> pluginService.startUpgrade(instance)));
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

  public record WechatPluginBatchRequest(List<String> instanceIds) {}

  public record WechatPluginBatchItem(String instanceId, PublicWechatPluginStatus plugin) {}
}
