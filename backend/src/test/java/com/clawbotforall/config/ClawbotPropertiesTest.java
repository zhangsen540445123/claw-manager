package com.clawbotforall.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClawbotPropertiesTest {

  @Test
  void defaultWechatQrTtlIsTwoMinutes() {
    ClawbotProperties properties = new ClawbotProperties(null, null, null, null);

    assertThat(properties.runtime().wechatQrTtlMs()).isEqualTo(120_000);
  }
}
