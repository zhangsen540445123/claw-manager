package com.clawbotforall.openviking;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.config.ClawbotProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenVikingIdentityServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void generatedSecretIsPersistedAndReusedAcrossServiceInstances() throws Exception {
    ClawbotProperties properties = properties();

    OpenVikingIdentityService first = new OpenVikingIdentityService(properties);
    String firstSecret = first.identityHashSecret();

    OpenVikingIdentityService second = new OpenVikingIdentityService(properties);
    String secondSecret = second.identityHashSecret();

    assertThat(firstSecret).isNotBlank();
    assertThat(secondSecret).isEqualTo(firstSecret);
    assertThat(Files.readString(tempDir.resolve("openviking").resolve("identity-hash-secret")).trim())
        .isEqualTo(firstSecret);
  }

  @Test
  void persistedSecretIsLoaded() throws Exception {
    persistSecret("persisted-secret");
    OpenVikingIdentityService service = new OpenVikingIdentityService(properties());

    assertThat(service.identityHashSecret()).isEqualTo("persisted-secret");
  }

  @Test
  void derivesStablePrivacyPreservingUserIdFromTrimmedSender() throws Exception {
    persistSecret("secret");
    OpenVikingIdentityService service = new OpenVikingIdentityService(properties());

    OpenVikingSenderIdentity first = service.resolveSenderIdentity("  wxid_Alpha  ").orElseThrow();
    OpenVikingSenderIdentity second = service.resolveSenderIdentity("wxid_Alpha").orElseThrow();
    OpenVikingSenderIdentity differentCase = service.resolveSenderIdentity("wxid_alpha").orElseThrow();

    assertThat(first.senderId()).isEqualTo("wxid_Alpha");
    assertThat(first.senderHash()).hasSize(32).matches("[0-9a-f]+");
    assertThat(first.openVikingUserId()).isEqualTo("wx_" + first.senderHash());
    assertThat(second).isEqualTo(first);
    assertThat(differentCase.openVikingUserId()).isNotEqualTo(first.openVikingUserId());
  }

  @Test
  void derivesDifferentUserIdWhenSaltChanges() {
    OpenVikingIdentityService service = new OpenVikingIdentityService(properties());

    OpenVikingSenderIdentity first = service.resolveSenderIdentity("wxid_Alpha", "salt-one").orElseThrow();
    OpenVikingSenderIdentity second = service.resolveSenderIdentity("wxid_Alpha", "salt-two").orElseThrow();

    assertThat(first.openVikingUserId()).startsWith("wx_");
    assertThat(second.openVikingUserId()).startsWith("wx_");
    assertThat(second.openVikingUserId()).isNotEqualTo(first.openVikingUserId());
  }

  @Test
  void blankOrNonStringSenderDoesNotProduceIdentity() throws Exception {
    persistSecret("secret");
    OpenVikingIdentityService service = new OpenVikingIdentityService(properties());

    assertThat(service.resolveSenderIdentity("   ")).isEmpty();
    assertThat(service.resolveSenderIdentity(null)).isEmpty();
    assertThat(service.resolveSenderIdentity(123)).isEmpty();
  }

  private void persistSecret(String secret) throws Exception {
    Path secretPath = tempDir.resolve("openviking").resolve("identity-hash-secret");
    Files.createDirectories(secretPath.getParent());
    Files.writeString(secretPath, secret);
  }

  private ClawbotProperties properties() {
    return new ClawbotProperties(
        new ClawbotProperties.Paths(tempDir.toString()),
        new ClawbotProperties.Admin("", "平台管理员", ""),
        new ClawbotProperties.Security("clawbot_session", 14),
        new ClawbotProperties.Runtime(
            "runner:latest",
            600_000,
            "1.0",
            "1g",
            600_000,
            120_000,
            1_800_000,
            10_000,
            5_000,
            java.util.List.of()
        )
    );
  }
}
