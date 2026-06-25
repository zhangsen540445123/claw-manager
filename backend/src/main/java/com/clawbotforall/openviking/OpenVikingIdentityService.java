package com.clawbotforall.openviking;

import com.clawbotforall.config.ClawbotProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OpenVikingIdentityService {

  private static final Logger log = LoggerFactory.getLogger(OpenVikingIdentityService.class);
  private static final int GENERATED_SECRET_BYTES = 32;

  private final ClawbotProperties properties;
  private volatile String identityHashSecret;

  public OpenVikingIdentityService(ClawbotProperties properties) {
    this.properties = properties;
  }

  public String identityHashSecret() {
    String cached = identityHashSecret;
    if (cached != null && !cached.isBlank()) {
      return cached;
    }
    synchronized (this) {
      if (identityHashSecret == null || identityHashSecret.isBlank()) {
        identityHashSecret = loadOrCreateSecret();
      }
      return identityHashSecret;
    }
  }

  public Optional<OpenVikingSenderIdentity> resolveSenderIdentity(Object rawSenderId) {
    return resolveSenderIdentity(rawSenderId, identityHashSecret());
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

  private String loadOrCreateSecret() {
    Path secretPath = secretPath();
    try {
      if (Files.exists(secretPath)) {
        String existing = Files.readString(secretPath, StandardCharsets.UTF_8).trim();
        if (!existing.isBlank()) {
          return existing;
        }
      }
      Files.createDirectories(secretPath.getParent());
      String generated = generateSecret();
      Files.writeString(secretPath, generated + System.lineSeparator(), StandardCharsets.UTF_8);
      log.info("OpenViking identity hash secret generated at {}", secretPath);
      return generated;
    } catch (Exception error) {
      throw new IllegalStateException("OpenViking identity hash secret 初始化失败。", error);
    }
  }

  private Path secretPath() {
    return Path.of(properties.paths().dataDir()).resolve("openviking").resolve("identity-hash-secret");
  }

  private static String generateSecret() {
    byte[] bytes = new byte[GENERATED_SECRET_BYTES];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
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
