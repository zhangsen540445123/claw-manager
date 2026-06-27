package com.clawbotforall.externalapi;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExternalApiAdminController {

  private final ExternalApiSettingsService settingsService;
  private final ExternalApiUserRouteMapper routeMapper;
  private final InstanceCommandService instanceCommandService;
  private final ExternalApiRouteService routeService;

  public ExternalApiAdminController(
      ExternalApiSettingsService settingsService,
      ExternalApiUserRouteMapper routeMapper,
      InstanceCommandService instanceCommandService,
      ExternalApiRouteService routeService
  ) {
    this.settingsService = settingsService;
    this.routeMapper = routeMapper;
    this.instanceCommandService = instanceCommandService;
    this.routeService = routeService;
  }

  @GetMapping("/api/admin/external-api/settings")
  public Map<String, Object> settings(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("settings", settingsService.publicSettings());
  }

  @PutMapping("/api/admin/external-api/settings")
  public Map<String, Object> updateSettings(
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("settings", settingsService.update(payload));
  }

  @GetMapping("/api/admin/external-api/users")
  public Map<String, Object> users(
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String instanceId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    int normalizedPage = Math.max(1, page);
    int normalizedPageSize = Math.max(1, Math.min(200, pageSize));
    int offset = (normalizedPage - 1) * normalizedPageSize;
    List<PublicExternalApiUserRoute> routes = routeMapper
        .list(keyword.trim(), instanceId.trim(), normalizedPageSize, offset)
        .stream()
        .map(PublicExternalApiUserRoute::from)
        .toList();
    int total = routeMapper.count(keyword.trim(), instanceId.trim());
    return Map.of(
        "routes", routes,
        "total", total,
        "page", normalizedPage,
        "pageSize", normalizedPageSize
    );
  }

  @PutMapping("/api/admin/external-api/users/route")
  public Map<String, Object> migrate(
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    String openidHash = payload == null ? "" : stringValue(payload.get("openidHash")).trim();
    String instanceId = payload == null ? "" : stringValue(payload.get("instanceId")).trim();
    if (openidHash.isBlank() || instanceId.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "openidHash 和 instanceId 不能为空。");
    }
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    routeService.requireUsableInstance(instance.getId());
    routeMapper.updateInstance(openidHash, instance.getId(), java.time.Instant.now().toString());
    ExternalApiUserRouteEntity route = routeMapper.findByOpenidHash(openidHash);
    return Map.of("route", PublicExternalApiUserRoute.from(route));
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
