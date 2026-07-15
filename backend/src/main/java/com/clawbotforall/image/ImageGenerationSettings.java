package com.clawbotforall.image;

public record ImageGenerationSettings(
    boolean enabled,
    String providerId,
    String modelId,
    String apiMode,
    String baseUrl,
    String apiKey,
    String providerConfig,
    int timeoutMs,
    String updatedAt
) {
  public static ImageGenerationSettings disabled() {
    return new ImageGenerationSettings(false, "", "", "", "", "", "{}", 180_000, "");
  }

  public boolean configured() {
    return enabled && !trim(providerId).isBlank() && !trim(modelId).isBlank() && !trim(apiKey).isBlank();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
