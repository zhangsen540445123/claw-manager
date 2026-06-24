package com.clawbotforall.openviking;

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
public class OpenVikingSettingsController {

  private final OpenVikingSettingsService settingsService;

  public OpenVikingSettingsController(OpenVikingSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping("/api/admin/openviking-settings")
  public Map<String, Object> settings(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("settings", settingsService.publicSettings());
  }

  @PutMapping("/api/admin/openviking-settings")
  public Map<String, Object> update(
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("settings", settingsService.updateSettings(payload));
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }
}
