package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class MiniappHmacAuthServiceTest {

  @Mock
  MiniappClientMapper clientMapper;

  @Mock
  MiniappRequestNonceMapper nonceMapper;

  MiniappHmacAuthService service;

  @BeforeEach
  void setUp() {
    service = new MiniappHmacAuthService(
        clientMapper,
        nonceMapper,
        Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC)
    );
  }

  @Test
  void acceptsValidSignatureAndStoresNonce() {
    MiniappClientEntity client = new MiniappClientEntity();
    client.setAppId("miniapp_main");
    client.setAppSecret("secret_1");
    client.setEnabled(true);
    when(clientMapper.findByAppId("miniapp_main")).thenReturn(client);

    String body = "{\"openid\":\"openid_1\"}";
    String signature = sign("secret_1", "POST", "/api/external/miniapp/wechat-bind-links", "1783159200000", "nonce_1", body);

    service.requireAuthorized(
        "POST",
        "/api/external/miniapp/wechat-bind-links",
        body,
        new MiniappHmacHeaders("miniapp_main", "1783159200000", "nonce_1", signature)
    );

    verify(nonceMapper).insert("miniapp_main", "nonce_1", "2026-07-04T10:00:00Z", "2026-07-04T10:05:00Z");
  }

  @Test
  void rejectsReplayNonce() {
    MiniappClientEntity client = new MiniappClientEntity();
    client.setAppId("miniapp_main");
    client.setAppSecret("secret_1");
    client.setEnabled(true);
    when(clientMapper.findByAppId("miniapp_main")).thenReturn(client);
    doThrow(new DuplicateKeyException("duplicate")).when(nonceMapper)
        .insert("miniapp_main", "nonce_1", "2026-07-04T10:00:00Z", "2026-07-04T10:05:00Z");

    String body = "{}";
    String signature = sign("secret_1", "GET", "/api/external/miniapp/wechat-bind-links/token_1", "1783159200000", "nonce_1", body);

    assertThatThrownBy(() -> service.requireAuthorized(
        "GET",
        "/api/external/miniapp/wechat-bind-links/token_1",
        body,
        new MiniappHmacHeaders("miniapp_main", "1783159200000", "nonce_1", signature)
    ))
        .isInstanceOf(ApiException.class)
        .hasMessage("小程序请求 nonce 已被使用。");
  }

  @Test
  void rejectsInvalidSignature() {
    MiniappClientEntity client = new MiniappClientEntity();
    client.setAppId("miniapp_main");
    client.setAppSecret("secret_1");
    client.setEnabled(true);
    when(clientMapper.findByAppId("miniapp_main")).thenReturn(client);

    assertThatThrownBy(() -> service.requireAuthorized(
        "POST",
        "/api/external/miniapp/wechat-bind-links",
        "{}",
        new MiniappHmacHeaders("miniapp_main", "1783159200000", "nonce_1", "bad")
    ))
        .isInstanceOf(ApiException.class)
        .hasMessage("小程序请求签名无效。");
  }

  private static String sign(String secret, String method, String path, String timestamp, String nonce, String body) {
    try {
      String canonical = String.join("\n", method, path, timestamp, nonce, sha256(body));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static String sha256(String body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
