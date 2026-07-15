package com.clawbotforall.image;

@FunctionalInterface
public interface ImageGenerationSettingsProvider {
  ImageGenerationSettings current();

  static ImageGenerationSettingsProvider disabled() {
    return ImageGenerationSettings::disabled;
  }
}
