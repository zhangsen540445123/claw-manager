package com.clawbotforall.openviking;

import com.clawbotforall.config.ClawbotProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
public class OpenVikingBrokerTokenService {

  private static final int GENERATED_TOKEN_BYTES = 32;

  private final ClawbotProperties properties;
  private volatile String token;

  public OpenVikingBrokerTokenService(ClawbotProperties properties) {
    this.properties = properties;
  }

  public String brokerToken() {
    String cached = token;
    if (cached != null && !cached.isBlank()) {
      return cached;
    }
    synchronized (this) {
      if (token == null || token.isBlank()) {
        token = loadOrCreateToken();
      }
      return token;
    }
  }

  public boolean matches(String candidate) {
    String normalized = candidate == null ? "" : candidate.trim();
    return !normalized.isBlank() && normalized.equals(brokerToken());
  }

  private String loadOrCreateToken() {
    Path tokenPath = Path.of(properties.paths().dataDir()).resolve("openviking").resolve("broker-token");
    try {
      if (Files.exists(tokenPath)) {
        String existing = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
        if (!existing.isBlank()) {
          return existing;
        }
      }
      Files.createDirectories(tokenPath.getParent());
      byte[] bytes = new byte[GENERATED_TOKEN_BYTES];
      new SecureRandom().nextBytes(bytes);
      String generated = HexFormat.of().formatHex(bytes);
      Files.writeString(tokenPath, generated + System.lineSeparator(), StandardCharsets.UTF_8);
      return generated;
    } catch (Exception error) {
      throw new IllegalStateException("OpenViking broker token 初始化失败。", error);
    }
  }
}
