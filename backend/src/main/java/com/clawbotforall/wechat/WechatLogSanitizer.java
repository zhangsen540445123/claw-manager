package com.clawbotforall.wechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class WechatLogSanitizer {
  private WechatLogSanitizer() {}

  public static String identityHashPreview(String value) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      return "absent";
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 不可用。", error);
    }
  }

  public static String present(String value) {
    return normalize(value).isBlank() ? "absent" : "present";
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
