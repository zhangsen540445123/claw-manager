package com.clawbotforall.image;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/image-generation-settings")
public class ImageGenerationSettingsController {
  private final ImageGenerationSettingsService service;
  private final ImageGenerationSettingsSyncService syncService;

  public ImageGenerationSettingsController(ImageGenerationSettingsService service, ImageGenerationSettingsSyncService syncService) {
    this.service = service;
    this.syncService = syncService;
  }

  @GetMapping
  public Map<String, Object> get(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("settings", service.getPublicSettings());
  }

  @PutMapping
  public Map<String, Object> save(@RequestBody(required = false) Map<String, Object> payload, Authentication authentication) {
    requireAdmin(authentication);
    PublicImageGenerationSettings settings = service.save(payload);
    return Map.of("settings", settings, "syncedInstanceIds", syncService.syncAll(), "restartRequired", true);
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }
}
