package com.clawbotforall.openviking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpenVikingIdentityServiceTest {

  @Test
  void noArgumentIdentitySecretIsNotAvailable() {
    OpenVikingIdentityService service = new OpenVikingIdentityService();

    assertThatThrownBy(service::identityHashSecret)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("openviking_settings.identity_salt");
    assertThatThrownBy(() -> service.resolveSenderIdentity("wxid_Alpha"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("显式传入数据库 identity_salt");
  }

  @Test
  void derivesStablePrivacyPreservingUserIdFromTrimmedSenderAndExplicitSalt() {
    OpenVikingIdentityService service = new OpenVikingIdentityService();

    OpenVikingSenderIdentity first = service.resolveSenderIdentity("  wxid_Alpha  ", "secret").orElseThrow();
    OpenVikingSenderIdentity second = service.resolveSenderIdentity("wxid_Alpha", "secret").orElseThrow();
    OpenVikingSenderIdentity differentCase = service.resolveSenderIdentity("wxid_alpha", "secret").orElseThrow();

    assertThat(first.senderId()).isEqualTo("wxid_Alpha");
    assertThat(first.senderHash()).hasSize(32).matches("[0-9a-f]+");
    assertThat(first.openVikingUserId()).isEqualTo("wx_" + first.senderHash());
    assertThat(second).isEqualTo(first);
    assertThat(differentCase.openVikingUserId()).isNotEqualTo(first.openVikingUserId());
  }

  @Test
  void derivesDifferentUserIdWhenSaltChanges() {
    OpenVikingIdentityService service = new OpenVikingIdentityService();

    OpenVikingSenderIdentity first = service.resolveSenderIdentity("wxid_Alpha", "salt-one").orElseThrow();
    OpenVikingSenderIdentity second = service.resolveSenderIdentity("wxid_Alpha", "salt-two").orElseThrow();

    assertThat(first.openVikingUserId()).startsWith("wx_");
    assertThat(second.openVikingUserId()).startsWith("wx_");
    assertThat(second.openVikingUserId()).isNotEqualTo(first.openVikingUserId());
  }

  @Test
  void blankSenderOrBlankSaltDoesNotProduceIdentity() {
    OpenVikingIdentityService service = new OpenVikingIdentityService();

    assertThat(service.resolveSenderIdentity("   ", "secret")).isEmpty();
    assertThat(service.resolveSenderIdentity(null, "secret")).isEmpty();
    assertThat(service.resolveSenderIdentity(123, "secret")).isEmpty();
    assertThat(service.resolveSenderIdentity("wxid_Alpha", "")).isEmpty();
    assertThat(service.resolveSenderIdentity("wxid_Alpha", "   ")).isEmpty();
  }
}
