package com.clawbotforall.heartbeat;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员只读扫描和显式轮换 Heartbeat 污染 Session 的接口。 */
@RestController
@RequestMapping("/api/admin/instances/{instanceId}/heartbeat-sessions")
public class HeartbeatSessionController {
  private final InstanceCommandService instanceCommandService;
  private final HeartbeatSessionScanner scanner;
  private final HeartbeatSessionMigrationService migrationService;

  public HeartbeatSessionController(
      InstanceCommandService instanceCommandService,
      HeartbeatSessionScanner scanner,
      HeartbeatSessionMigrationService migrationService
  ) {
    this.instanceCommandService = instanceCommandService;
    this.scanner = scanner;
    this.migrationService = migrationService;
  }

  @GetMapping
  public Map<String, Object> scan(
      @PathVariable String instanceId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    instanceCommandService.requireInstance(instanceId);
    return Map.of("report", scanner.scanInstance(instanceId));
  }

  @PostMapping("/reset")
  public Map<String, Object> reset(
      @PathVariable String instanceId,
      @RequestBody(required = false) ResetSessionsRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    if (request == null || !request.confirm()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "必须明确确认后才能轮换污染 Session。");
    }
    List<String> hashes = request.sessionKeyHashes() == null
        ? List.of() : List.copyOf(request.sessionKeyHashes());
    if (hashes.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "至少选择一个 Session。");
    }
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    return Map.of("report", migrationService.resetSessions(instance, hashes));
  }

  private static AuthenticatedAdmin requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin admin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录管理员账号。");
    }
    return admin;
  }

  public record ResetSessionsRequest(List<String> sessionKeyHashes, boolean confirm) {}
}
