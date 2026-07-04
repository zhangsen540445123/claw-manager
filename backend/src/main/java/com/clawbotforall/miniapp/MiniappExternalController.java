package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MiniappExternalController {
  private final MiniappHmacAuthService authService;
  private final MiniappBindingService bindingService;
  private final MiniappUserAccessService userAccessService;
  private final ObjectMapper objectMapper;

  public MiniappExternalController(
      MiniappHmacAuthService authService,
      MiniappBindingService bindingService,
      MiniappUserAccessService userAccessService,
      ObjectMapper objectMapper
  ) {
    this.authService = authService;
    this.bindingService = bindingService;
    this.userAccessService = userAccessService;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/api/external/miniapp/wechat-bind-links")
  public Map<String, Object> createWechatBindLink(
      @RequestBody(required = false) String body,
      @RequestHeader(value = "X-CM-App-Id", required = false) String appId,
      @RequestHeader(value = "X-CM-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-CM-Nonce", required = false) String nonce,
      @RequestHeader(value = "X-CM-Signature", required = false) String signature,
      HttpServletRequest request
  ) {
    String raw = body == null ? "" : body;
    authService.requireAuthorized(request.getMethod(), pathWithQuery(request), raw, new MiniappHmacHeaders(appId, timestamp, nonce, signature));
    CreateBindLinkRequest payload = read(raw, CreateBindLinkRequest.class);
    return Map.of("binding", publicBinding(bindingService.createWechatBindLink(payload.openid(), origin(request))));
  }

  @GetMapping("/api/external/miniapp/wechat-bind-links/{token}")
  public Map<String, Object> getWechatBindLink(
      @PathVariable String token,
      @RequestHeader(value = "X-CM-App-Id", required = false) String appId,
      @RequestHeader(value = "X-CM-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-CM-Nonce", required = false) String nonce,
      @RequestHeader(value = "X-CM-Signature", required = false) String signature,
      HttpServletRequest request
  ) {
    authService.requireAuthorized(request.getMethod(), pathWithQuery(request), "", new MiniappHmacHeaders(appId, timestamp, nonce, signature));
    return Map.of("binding", publicBinding(bindingService.getBindLink(token, origin(request))));
  }

  @PostMapping("/api/external/miniapp/user-keys")
  public Map<String, Object> createUserKey(
      @RequestBody(required = false) String body,
      @RequestHeader(value = "X-CM-App-Id", required = false) String appId,
      @RequestHeader(value = "X-CM-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-CM-Nonce", required = false) String nonce,
      @RequestHeader(value = "X-CM-Signature", required = false) String signature,
      HttpServletRequest request
  ) {
    String raw = body == null ? "" : body;
    authService.requireAuthorized(request.getMethod(), pathWithQuery(request), raw, new MiniappHmacHeaders(appId, timestamp, nonce, signature));
    UserKeyRequest payload = read(raw, UserKeyRequest.class);
    return Map.of("userKey", publicUserKey(userAccessService.createOrGetUserKey(payload.openid(), payload.reset())));
  }

  private <T> T read(String body, Class<T> type) {
    try {
      return objectMapper.readValue(body == null || body.isBlank() ? "{}" : body, type);
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请求 JSON 格式无效。");
    }
  }

  private static Map<String, Object> publicBinding(MiniappBindLinkResult result) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("openid", result.openid());
    response.put("bindToken", result.bindToken());
    response.put("status", result.status());
    response.put("instanceId", result.instanceId());
    response.put("openVikingUserId", defaultString(result.openVikingUserId()));
    response.put("canCreateUserKey", result.canCreateUserKey());
    if (result.link() != null) {
      response.put("qrLink", result.link().qrLink());
      response.put("qrPayload", result.link().qrPayload());
      response.put("expiresAt", result.link().expiresAt());
    }
    return response;
  }

  private static Map<String, Object> publicUserKey(MiniappUserKeyResult result) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("openid", result.openid());
    response.put("key", result.key());
    response.put("keyPreview", result.keyPreview());
    response.put("openVikingUserId", result.openVikingUserId());
    response.put("instanceId", result.instanceId());
    response.put("created", result.created());
    return response;
  }

  private static String pathWithQuery(HttpServletRequest request) {
    String query = request.getQueryString();
    return request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query);
  }

  private static String origin(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if (origin == null || origin.isBlank()) {
      return "";
    }
    return origin;
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  public record CreateBindLinkRequest(String openid) {}

  public record UserKeyRequest(String openid, boolean reset) {}
}
