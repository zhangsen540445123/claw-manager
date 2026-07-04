package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MiniappHmacAuthService {
  private static final Duration TIMESTAMP_SKEW = Duration.ofMinutes(5);
  private static final Duration NONCE_TTL = Duration.ofMinutes(5);

  private final MiniappClientMapper clientMapper;
  private final MiniappRequestNonceMapper nonceMapper;
  private final Clock clock;

  @Autowired
  public MiniappHmacAuthService(
      MiniappClientMapper clientMapper,
      MiniappRequestNonceMapper nonceMapper
  ) {
    this(clientMapper, nonceMapper, Clock.systemUTC());
  }

  MiniappHmacAuthService(
      MiniappClientMapper clientMapper,
      MiniappRequestNonceMapper nonceMapper,
      Clock clock
  ) {
    this.clientMapper = clientMapper;
    this.nonceMapper = nonceMapper;
    this.clock = clock;
  }

  public void requireAuthorized(String method, String pathWithQuery, String rawBody, MiniappHmacHeaders headers) {
    String appId = trim(headers == null ? null : headers.appId());
    String timestamp = trim(headers == null ? null : headers.timestamp());
    String nonce = trim(headers == null ? null : headers.nonce());
    String signature = trim(headers == null ? null : headers.signature()).toLowerCase();
    if (appId.isBlank() || timestamp.isBlank() || nonce.isBlank() || signature.isBlank()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序 HMAC 鉴权头缺失。");
    }
    MiniappClientEntity client = clientMapper.findByAppId(appId);
    if (client == null || !client.isEnabled() || trim(client.getAppSecret()).isBlank()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序调用方不存在或已禁用。");
    }
    Instant now = clock.instant();
    Instant requestTime = parseTimestamp(timestamp);
    if (requestTime.isBefore(now.minus(TIMESTAMP_SKEW)) || requestTime.isAfter(now.plus(TIMESTAMP_SKEW))) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序请求时间戳已过期。");
    }
    String expected = sign(client.getAppSecret(), method, pathWithQuery, timestamp, nonce, rawBody == null ? "" : rawBody);
    if (!constantTimeEquals(expected, signature)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序请求签名无效。");
    }
    nonceMapper.deleteExpired(now.toString());
    try {
      nonceMapper.insert(appId, nonce, now.toString(), now.plus(NONCE_TTL).toString());
    } catch (DuplicateKeyException error) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序请求 nonce 已被使用。");
    }
  }

  private static Instant parseTimestamp(String timestamp) {
    try {
      return Instant.ofEpochMilli(Long.parseLong(timestamp));
    } catch (NumberFormatException error) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序请求时间戳无效。");
    }
  }

  private static String sign(String secret, String method, String pathWithQuery, String timestamp, String nonce, String body) {
    try {
      String canonical = String.join("\n",
          trim(method).toUpperCase(),
          pathWithQuery == null ? "" : pathWithQuery,
          timestamp,
          nonce,
          sha256(body)
      );
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("小程序请求签名计算失败。", error);
    }
  }

  private static String sha256(String body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("小程序请求摘要计算失败。", error);
    }
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        actual.getBytes(StandardCharsets.UTF_8)
    );
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
