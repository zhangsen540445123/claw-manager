package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MiniappBridgeService {
  private static final Logger log = LoggerFactory.getLogger(MiniappBridgeService.class);

  private final MiniappUserBindingMapper bindingMapper;
  private final MiniappUserKeyMapper keyMapper;
  private final RestClient restClient;
  private final String baseUrl;
  private final Clock clock;
  private final MiniappBridgeActionRegistry actionRegistry;

  @Autowired
  public MiniappBridgeService(
      MiniappUserBindingMapper bindingMapper,
      MiniappUserKeyMapper keyMapper,
      RestClient.Builder restClientBuilder,
      @Value("${clawbot.miniapp-open-api-base-url:}") String baseUrl,
      MiniappBridgeActionRegistry actionRegistry
  ) {
    this(bindingMapper, keyMapper, restClientBuilder, baseUrl, Clock.systemUTC(), actionRegistry);
  }

  MiniappBridgeService(
      MiniappUserBindingMapper bindingMapper,
      MiniappUserKeyMapper keyMapper,
      RestClient.Builder restClientBuilder,
      String baseUrl,
      Clock clock,
      MiniappBridgeActionRegistry actionRegistry
  ) {
    this.bindingMapper = bindingMapper;
    this.keyMapper = keyMapper;
    this.restClient = restClientBuilder.build();
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.clock = clock;
    this.actionRegistry = actionRegistry;
  }

  public Object execute(String actionKey, MiniappBridgeRequest request) {
    long startedAt = System.nanoTime();
    if (baseUrl.isBlank()) throw new ApiException(HttpStatus.CONFLICT, "小程序 Open API 地址尚未配置。");
    if (request == null || blank(request.instanceId()) || blank(request.requesterSenderId())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "缺少实例或发送者身份。");
    }
    MiniappUserBindingEntity binding = resolveBinding(request.requesterSenderId());
    if (binding == null || !"connected".equals(binding.getBindStatus())) {
      throw new ApiException(HttpStatus.CONFLICT, "当前用户尚未完成小程序微信绑定。");
    }
    if (!request.instanceId().equals(binding.getInstanceId())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "发送者不属于当前 OpenClaw 实例。");
    }
    MiniappUserKeyEntity key = keyMapper.findByOpenidHash(binding.getOpenidHash());
    if (key == null || !key.isEnabled() || blank(key.getUserKey())) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "当前用户没有可用的小程序用户 Key。");
    }

    MiniappBridgePreparedAction action = actionRegistry.prepare(actionKey,
        request.parameters() == null ? Map.of() : request.parameters());
    String bridgeRequestId = safeRequestId(request.requestId());
    URI uri = uri(action);
    String senderType = request.requesterSenderId().startsWith("miniapp:") ? "miniapp" : "wechat";
    log.info("miniapp.bridge.start bridgeRequestId={} instanceId={} senderType={} openidHash={} toolDomain={} operation={} actionKey={} targetPath={} parameterNames={}",
        bridgeRequestId, request.instanceId(), senderType, binding.getOpenidHash(), action.domain(), action.operation(),
        action.actionKey(), action.path(), request.parameters() == null ? "[]" : request.parameters().keySet());
    try {
      RestClient.RequestBodySpec spec = restClient.method(action.method()).uri(uri)
          .header("X-Open-Api-Openid", key.getOpenid())
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.getUserKey())
          .header("X-CM-Bridge-Request-Id", bridgeRequestId);
      ResponseEntity<Object> response = action.body().isEmpty()
          ? spec.retrieve().toEntity(Object.class)
          : spec.body(action.body()).retrieve().toEntity(Object.class);
      Object body = response.getBody() == null ? Map.of() : response.getBody();
      int businessCode = businessCode(body);
      if (businessCode != 200) {
        throw new ApiException(HttpStatus.BAD_GATEWAY, businessMessage(body, businessCode));
      }
      keyMapper.updateLastUsed(key.getOpenidHash(), clock.instant().toString());
      log.info("miniapp.bridge.done bridgeRequestId={} instanceId={} openidHash={} toolDomain={} operation={} actionKey={} httpStatus={} businessCode={} elapsedMs={}",
          bridgeRequestId, request.instanceId(), binding.getOpenidHash(), action.domain(), action.operation(), action.actionKey(),
          response.getStatusCode().value(), businessCode, elapsedMs(startedAt));
      return body;
    } catch (RuntimeException error) {
      log.warn("miniapp.bridge.error bridgeRequestId={} instanceId={} openidHash={} toolDomain={} operation={} actionKey={} elapsedMs={} error={}",
          bridgeRequestId, request.instanceId(), binding.getOpenidHash(), action.domain(), action.operation(), action.actionKey(),
          elapsedMs(startedAt), safeError(error));
      throw error;
    }
  }

  private URI uri(MiniappBridgePreparedAction action) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + action.path());
    action.query().forEach(builder::queryParam);
    return builder.build().encode().toUri();
  }

  private MiniappUserBindingEntity resolveBinding(String requesterSenderId) {
    String sender = requesterSenderId.trim();
    return sender.startsWith("miniapp:")
        ? bindingMapper.findByOpenidHash(sender.substring("miniapp:".length()))
        : bindingMapper.findByWechatUserId(sender);
  }

  private int businessCode(Object body) {
    if (!(body instanceof Map<?, ?> map) || !map.containsKey("code")) return 200;
    Object value = map.get("code");
    try { return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value)); }
    catch (NumberFormatException ignored) { return 500; }
  }

  private String businessMessage(Object body, int code) {
    if (body instanceof Map<?, ?> map && map.get("message") != null) return "小程序业务接口失败(" + code + "): " + map.get("message");
    return "小程序业务接口失败(" + code + ")。";
  }

  private static String safeRequestId(String value) {
    String normalized = value == null ? "" : value.trim();
    return normalized.isBlank() ? "mbreq_unknown" : normalized.substring(0, Math.min(100, normalized.length()));
  }
  private static String safeError(Throwable error) {
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    String sanitized = message.replaceAll("cm_user_[A-Za-z0-9_-]+", "cm_user_***");
    return sanitized.substring(0, Math.min(500, sanitized.length()));
  }
  private static long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static String trimTrailingSlash(String value) { return value == null ? "" : value.trim().replaceFirst("/+$", ""); }
}
