package com.clawbotforall.instance;

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

/** 实例删除任务查询和重试 API。 */
@RestController
@RequestMapping("/api/admin/instance-delete-operations")
public class InstanceDeleteOperationController {
  private final InstanceDeletionService instanceDeletionService;

  public InstanceDeleteOperationController(InstanceDeletionService instanceDeletionService) {
    this.instanceDeletionService = instanceDeletionService;
  }

  @GetMapping("/{operationId}")
  public Map<String, Object> get(
      @PathVariable String operationId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("operation", PublicInstanceDeleteOperation.from(instanceDeletionService.find(operationId)));
  }

  @PostMapping("/{operationId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> retry(
      @PathVariable String operationId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("operation", PublicInstanceDeleteOperation.from(instanceDeletionService.retry(operationId)));
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录管理员账号。");
    }
  }
}
