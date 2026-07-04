package com.clawbotforall.miniapp;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MiniappAdminController {
  private final MiniappClientAdminService service;

  public MiniappAdminController(MiniappClientAdminService service) {
    this.service = service;
  }

  @GetMapping("/api/admin/miniapp-clients")
  public Map<String, Object> list(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("clients", service.listClients());
  }

  @PostMapping("/api/admin/miniapp-clients")
  public Map<String, Object> create(
      @RequestBody(required = false) MiniappClientRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("client", service.createClient(
        request == null ? "" : request.appId(),
        request == null || request.enabled()
    ));
  }

  @PutMapping("/api/admin/miniapp-clients/{appId}")
  public Map<String, Object> update(
      @PathVariable String appId,
      @RequestBody(required = false) MiniappClientRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("client", service.updateEnabled(appId, request != null && request.enabled()));
  }

  @PostMapping("/api/admin/miniapp-clients/{appId}/secret/reset")
  public Map<String, Object> resetSecret(
      @PathVariable String appId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("client", service.resetSecret(appId));
  }

  @DeleteMapping("/api/admin/miniapp-clients/{appId}")
  public Map<String, Object> delete(
      @PathVariable String appId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    service.deleteClient(appId);
    return Map.of("ok", true);
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }

  public record MiniappClientRequest(String appId, boolean enabled) {}
}
