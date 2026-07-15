package com.clawbotforall.image;

public record PublicImageGenerationSettings(
    boolean enabled,
    boolean configured,
    String providerId,
    String modelId,
    String apiMode,
    String baseUrl,
    String apiKeyPreview,
    String providerConfig,
    int timeoutMs,
    String updatedAt
) {
  static PublicImageGenerationSettings from(ImageGenerationSettings settings) {
    return new PublicImageGenerationSettings(
        settings.enabled(), settings.configured(), safe(settings.providerId()), safe(settings.modelId()),
        safe(settings.apiMode()), safe(settings.baseUrl()), preview(settings.apiKey()),
        safe(settings.providerConfig()).isBlank() ? "{}" : settings.providerConfig(), settings.timeoutMs(), safe(settings.updatedAt())
    );
  }

  private static String preview(String secret) {
    String value = safe(secret).trim();
    if (value.isBlank()) return "";
    if (value.length() <= 8) return "****";
    return value.substring(0, 3) + "..." + value.substring(value.length() - 4);
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
