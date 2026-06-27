package com.clawbotforall.externalapi;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExternalApiIdentityService {

  private final ClawbotProperties properties;

  public ExternalApiIdentityService(ClawbotProperties properties) {
    this.properties = properties;
  }

  public ExternalApiIdentity resolve(String openid, String identitySalt) {
    String normalized = openid == null ? "" : openid.trim();
    if (normalized.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "openid 不能为空。");
    }
    String salt = identitySalt == null ? "" : identitySalt.trim();
    if (salt.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking 身份盐值未配置。");
    }
    String hash = hmacSha256Hex(salt, normalized).substring(0, 32);
    return new ExternalApiIdentity(normalized, hash, "api_" + hash, "api:" + hash);
  }

  public String conversationHash(String conversationId, String identitySalt) {
    String normalized = conversationId == null || conversationId.trim().isBlank() ? "default" : conversationId.trim();
    String salt = identitySalt == null ? "" : identitySalt.trim();
    if (salt.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "OpenViking 身份盐值未配置。");
    }
    return hmacSha256Hex(salt, normalized).substring(0, 16);
  }

  private static String hmacSha256Hex(String secret, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException error) {
      throw new IllegalStateException("无法生成 API 用户身份。", error);
    }
  }
}
