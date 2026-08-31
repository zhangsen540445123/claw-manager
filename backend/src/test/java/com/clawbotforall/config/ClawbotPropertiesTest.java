package com.clawbotforall.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClawbotPropertiesTest {

  @Test
  void defaultWechatQrTtlIsTwoMinutes() {
    ClawbotProperties properties = new ClawbotProperties(null, null, null, null);

    assertThat(properties.runtime().wechatQrTtlMs()).isEqualTo(120_000);
  }

  @Test
  void defaultControlUiAllowedOriginsUsesWildcard() {
    ClawbotProperties properties = new ClawbotProperties(null, null, null, null);

    assertThat(properties.runtime().controlUiAllowedOrigins()).containsExactly("*");
  }

  @Test
  void defaultAgentHeartbeatIsDisabledAndSafe() {
    ClawbotProperties properties = new ClawbotProperties(null, null, null, null);

    assertThat(properties.runtime().agentHeartbeatEnabled()).isFalse();
    assertThat(properties.runtime().agentHeartbeatEvery()).isEqualTo("30m");
    assertThat(properties.runtime().agentHeartbeatIsolatedSession()).isTrue();
    assertThat(properties.runtime().agentHeartbeatLightContext()).isTrue();
    assertThat(properties.runtime().agentHeartbeatDirectPolicy()).isEqualTo("block");
  }

  @Test
  void defaultRunnerResourcesRemainStable() {
    ClawbotProperties properties = new ClawbotProperties(null, null, null, null);

    assertThat(properties.runtime().runnerCpus()).isEqualTo("1");
    assertThat(properties.runtime().runnerMemory()).isEqualTo("2g");
    assertThat(properties.runtime().runnerMemorySwap()).isEqualTo("4g");
    assertThat(properties.runtime().runnerNodeMaxOldSpaceMb()).isEqualTo(1536);
  }
}
