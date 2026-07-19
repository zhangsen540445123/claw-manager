package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import com.clawbotforall.web.ExternalRequestIds;
import com.clawbotforall.wechat.WechatLogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MiniappExternalController {
  private static final Logger log = LoggerFactory.getLogger(MiniappExternalController.class);

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
      HttpServletRequest request,
      HttpServletResponse response
  ) {
    long startedAt = System.nanoTime();
    String cmRequestId = prepareRequestId(response);
    String raw = body == null ? "" : body;
    try {
      authService.requireAuthorized(request.getMethod(), pathWithQuery(request), raw, new MiniappHmacHeaders(appId, timestamp, nonce, signature));
      CreateBindLinkRequest payload = read(raw, CreateBindLinkRequest.class);
      MiniappBindLinkResult result = bindingService.createWechatBindLink(payload.openid(), origin(request));
      log.info(
          "miniapp.bindLink.create success cmRequestId={} appId={} openidHash={} bindTokenPresent={} status={} instanceId={} openVikingUserHash={} elapsedMs={}",
          cmRequestId,
          safe(appId),
          WechatLogSanitizer.identityHashPreview(payload.openid()),
          WechatLogSanitizer.present(result.bindToken()),
          safe(result.status()),
          safe(result.instanceId()),
          WechatLogSanitizer.identityHashPreview(result.openVikingUserId()),
          elapsedMs(startedAt)
      );
      return Map.of("binding", publicBinding(result));
    } catch (RuntimeException error) {
      log.warn(
          "miniapp.bindLink.create failed cmRequestId={} appId={} elapsedMs={} errorType={}",
          cmRequestId,
          safe(appId),
          elapsedMs(startedAt),
          error.getClass().getSimpleName()
      );
      throw error;
    }
  }

  @GetMapping("/api/external/miniapp/wechat-bind-links/{token}")
  public Map<String, Object> getWechatBindLink(
      @PathVariable String token,
      @RequestHeader(value = "X-CM-App-Id", required = false) String appId,
      @RequestHeader(value = "X-CM-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-CM-Nonce", required = false) String nonce,
      @RequestHeader(value = "X-CM-Signature", required = false) String signature,
      HttpServletRequest request,
      HttpServletResponse response
  ) {
    long startedAt = System.nanoTime();
    String cmRequestId = prepareRequestId(response);
    try {
      authService.requireAuthorized(request.getMethod(), pathWithQuery(request), "", new MiniappHmacHeaders(appId, timestamp, nonce, signature));
      MiniappBindLinkResult result = bindingService.getBindLink(token, origin(request));
      log.info(
          "miniapp.bindLink.get success cmRequestId={} appId={} bindTokenPresent={} status={} instanceId={} openVikingUserHash={} elapsedMs={}",
          cmRequestId,
          safe(appId),
          WechatLogSanitizer.present(result.bindToken()),
          safe(result.status()),
          safe(result.instanceId()),
          WechatLogSanitizer.identityHashPreview(result.openVikingUserId()),
          elapsedMs(startedAt)
      );
      return Map.of("binding", publicBinding(result));
    } catch (RuntimeException error) {
      log.warn(
          "miniapp.bindLink.get failed cmRequestId={} appId={} bindTokenPresent={} elapsedMs={} errorType={}",
          cmRequestId,
          safe(appId),
          WechatLogSanitizer.present(token),
          elapsedMs(startedAt),
          error.getClass().getSimpleName()
      );
      throw error;
    }
  }

  @PostMapping("/api/external/miniapp/user-keys")
  public Map<String, Object> createUserKey(
      @RequestBody(required = false) String body,
      @RequestHeader(value = "X-CM-App-Id", required = false) String appId,
      @RequestHeader(value = "X-CM-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-CM-Nonce", required = false) String nonce,
      @RequestHeader(value = "X-CM-Signature", required = false) String signature,
      HttpServletRequest request,
      HttpServletResponse response
  ) {
    long startedAt = System.nanoTime();
    String cmRequestId = prepareRequestId(response);
    String raw = body == null ? "" : body;
    try {
      authService.requireAuthorized(request.getMethod(), pathWithQuery(request), raw, new MiniappHmacHeaders(appId, timestamp, nonce, signature));
      UserKeyRequest payload = read(raw, UserKeyRequest.class);
      MiniappUserKeyResult result = userAccessService.createOrGetUserKey(payload.openid(), payload.reset());
      log.info(
          "miniapp.userKey.create success cmRequestId={} appId={} openidHash={} key={} created={} instanceId={} openVikingUserHash={} elapsedMs={}",
          cmRequestId,
          safe(appId),
          WechatLogSanitizer.identityHashPreview(payload.openid()),
          WechatLogSanitizer.present(result.keyPreview()),
          result.created(),
          safe(result.instanceId()),
          WechatLogSanitizer.identityHashPreview(result.openVikingUserId()),
          elapsedMs(startedAt)
      );
      return Map.of("userKey", publicUserKey(result));
    } catch (RuntimeException error) {
      log.warn(
          "miniapp.userKey.create failed cmRequestId={} appId={} elapsedMs={} errorType={}",
          cmRequestId,
          safe(appId),
          elapsedMs(startedAt),
          error.getClass().getSimpleName()
      );
      throw error;
    }
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

  private static String prepareRequestId(HttpServletResponse response) {
    String cmRequestId = ExternalRequestIds.create();
    response.setHeader(ExternalRequestIds.HEADER, cmRequestId);
    return cmRequestId;
  }

  private static long elapsedMs(long startedAt) {
    return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private static String safe(String value) {
    String normalized = defaultString(value).trim();
    return normalized.isBlank() ? "-" : normalized;
  }

  public record CreateBindLinkRequest(String openid) {}

  public record UserKeyRequest(String openid, boolean reset) {}
}
