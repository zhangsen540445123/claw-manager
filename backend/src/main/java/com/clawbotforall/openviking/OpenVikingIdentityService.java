package com.clawbotforall.openviking;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class OpenVikingIdentityService {

  public OpenVikingIdentityService() {
  }

  public String identityHashSecret() {
    throw new IllegalStateException("OpenViking identity salt 必须来自数据库 openviking_settings.identity_salt。");
  }

  public Optional<OpenVikingSenderIdentity> resolveSenderIdentity(Object rawSenderId) {
    throw new IllegalStateException("OpenViking sender identity 必须显式传入数据库 identity_salt。");
  }

  public Optional<OpenVikingSenderIdentity> resolveSenderIdentity(Object rawSenderId, String identitySalt) {
    if (!(rawSenderId instanceof String senderId)) {
      return Optional.empty();
    }
    String normalized = senderId.trim();
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    String salt = identitySalt == null ? "" : identitySalt.trim();
    if (salt.isBlank()) {
      return Optional.empty();
    }
    String senderHash = hmacSha256Hex(salt, normalized).substring(0, 32);
    return Optional.of(new OpenVikingSenderIdentity(normalized, senderHash, "wx_" + senderHash));
  }

  private static String hmacSha256Hex(String secret, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("OpenViking sender identity HMAC 计算失败。", error);
    }
  }
}
