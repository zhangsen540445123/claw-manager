package com.clawbotforall.wechat;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }
}
