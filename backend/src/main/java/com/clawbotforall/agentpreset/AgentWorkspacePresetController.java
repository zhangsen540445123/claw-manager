package com.clawbotforall.agentpreset;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentWorkspacePresetController {
  private final AgentWorkspacePresetService service;
  private final AgentWorkspacePresetPushService pushService;

  public AgentWorkspacePresetController(
      AgentWorkspacePresetService service,
      AgentWorkspacePresetPushService pushService
  ) {
    this.service = service;
    this.pushService = pushService;
  }

  @GetMapping("/api/admin/agent-workspace-preset")
  public Map<String, Object> get(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("preset", service.publicPreset());
  }

  @PutMapping("/api/admin/agent-workspace-preset")
  public Map<String, Object> update(@RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("preset", service.update(payload));
  }

  @PostMapping("/api/admin/agent-workspace-preset/push")
  public Map<String, Object> push(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("result", pushService.push());
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }
}
