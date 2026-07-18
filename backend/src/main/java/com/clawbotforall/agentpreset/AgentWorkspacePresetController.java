package com.clawbotforall.agentpreset;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentWorkspacePresetController {
  private final AgentWorkspacePresetService service;

  public AgentWorkspacePresetController(AgentWorkspacePresetService service) {
    this.service = service;
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

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }
}
