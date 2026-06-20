package com.clawbotforall.wechat;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理已绑定微信账号的运行通道。
 */
@RestController
public class WechatChannelController {

  private final InstanceCommandService commandService;
  private final WechatChannelRestartService restartService;

  public WechatChannelController(
      InstanceCommandService commandService,
      WechatChannelRestartService restartService
  ) {
    this.commandService = commandService;
    this.restartService = restartService;
  }

  @PostMapping("/api/admin/instances/{instanceId}/wechat-accounts/{accountId}/restart-channel")
  public Map<String, Object> restartAccount(
      @PathVariable String instanceId,
      @PathVariable String accountId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = commandService.requireInstance(instanceId);
    return Map.of("account", restartService.restartAccount(instance, accountId));
  }

  @PostMapping("/api/admin/wechat-accounts/restart-channel")
  public Map<String, Object> restartAccounts(
      @RequestBody(required = false) RestartWechatAccountsRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    List<Object> results = new ArrayList<>();
    for (RestartWechatAccountRequest account : request == null || request.accounts() == null ? List.<RestartWechatAccountRequest>of() : request.accounts()) {
      if (account == null) {
        continue;
      }
      String instanceId = defaultString(account.instanceId()).trim();
      String accountId = defaultString(account.accountId()).trim();
      try {
        InstanceEntity instance = commandService.requireInstance(instanceId);
        results.add(restartService.restartAccount(instance, accountId));
      } catch (RuntimeException error) {
        results.add(new WechatChannelRestartService.RestartWechatChannelResult(
            instanceId,
            accountId,
            "failed",
            message(error)
        ));
      }
    }
    return Map.of("accounts", results);
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }

  private static String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? "微信通道重启失败。" : message;
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  public record RestartWechatAccountsRequest(List<RestartWechatAccountRequest> accounts) {}

  public record RestartWechatAccountRequest(String instanceId, String accountId) {}
}
