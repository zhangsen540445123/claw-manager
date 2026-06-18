package com.clawbotforall.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Component;

/**
 * 将 scrypt 哈希和校验委托给 Node.js 执行的密码哈希器。
 */
@Component
public class NodeScryptPasswordHasher implements PasswordHasher {

  private static final int SALT_BYTES = 16;
  private static final int KEY_LENGTH_BYTES = 64;
  private static final int CPU_COST = 16_384;
  private static final int BLOCK_SIZE = 8;
  private static final int PARALLELIZATION = 1;

  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * 将明文密码哈希为可存储形式。
   */

  @Override
  public HashedPassword hash(String password) {
    byte[] saltBytes = new byte[SALT_BYTES];
    secureRandom.nextBytes(saltBytes);
    String salt = HexFormat.of().formatHex(saltBytes);
    return new HashedPassword(salt, hashWithSalt(password, salt));
  }

  /**
   * 根据已存储哈希校验明文密码。
   */

  @Override
  public boolean verify(String password, String salt, String expectedHash) {
    if (password == null || salt == null || expectedHash == null) {
      return false;
    }

    String candidate = hashWithSalt(password, salt);
    return MessageDigest.isEqual(
        candidate.getBytes(StandardCharsets.UTF_8),
        expectedHash.getBytes(StandardCharsets.UTF_8)
    );
  }

  String hashWithSalt(String password, String salt) {
    byte[] key = SCrypt.generate(
        String.valueOf(password).getBytes(StandardCharsets.UTF_8),
        salt.getBytes(StandardCharsets.UTF_8),
        CPU_COST,
        BLOCK_SIZE,
        PARALLELIZATION,
        KEY_LENGTH_BYTES
    );
    return HexFormat.of().formatHex(key);
  }
}
