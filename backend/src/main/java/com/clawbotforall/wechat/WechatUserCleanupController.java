package com.clawbotforall.wechat;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 用户中心及可恢复清理任务管理接口。 */
@RestController
@RequestMapping("/api/admin")
public class WechatUserCleanupController {
  private final WechatUserQueryService queryService;
  private final WechatUserCleanupService cleanupService;

  public WechatUserCleanupController(
      WechatUserQueryService queryService,
      WechatUserCleanupService cleanupService
  ) {
    this.queryService = queryService;
    this.cleanupService = cleanupService;
  }

  @GetMapping("/wechat-users")
  public Map<String, Object> listUsers(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("users", queryService.listUsers());
  }

  @GetMapping("/wechat-user-cleanups/{operationId}")
  public Map<String, Object> find(
      @PathVariable String operationId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("operation", PublicWechatUserCleanupOperation.from(cleanupService.find(operationId)));
  }

  @PostMapping("/wechat-user-cleanups/{operationId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> retry(
      @PathVariable String operationId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("operation", PublicWechatUserCleanupOperation.from(cleanupService.retry(operationId)));
  }

  private static AuthenticatedAdmin requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin admin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录管理员账号。");
    }
    return admin;
  }
}
