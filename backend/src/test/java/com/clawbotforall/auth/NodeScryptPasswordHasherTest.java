package com.clawbotforall.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NodeScryptPasswordHasherTest {

  private final NodeScryptPasswordHasher passwordHasher = new NodeScryptPasswordHasher();

  @Test
  void matchesNodeCryptoScryptSyncOutput() {
    String hash = passwordHasher.hashWithSalt("11111111", "0123456789abcdef0123456789abcdef");

    assertThat(hash).isEqualTo(
        "1aa222f4bc267f2b95a912ba976c0893efb3b43a1e1c0e3ab868a37c8cc90c8a"
            + "8923a4873ea9b4a64e76880ec2d0e0a74d5a2bb9039e99918794c294333d9ddc"
    );
  }

  @Test
  void verifiesExpectedHash() {
    assertThat(passwordHasher.verify(
        "11111111",
        "0123456789abcdef0123456789abcdef",
        "1aa222f4bc267f2b95a912ba976c0893efb3b43a1e1c0e3ab868a37c8cc90c8a"
            + "8923a4873ea9b4a64e76880ec2d0e0a74d5a2bb9039e99918794c294333d9ddc"
    )).isTrue();
  }

  @Test
  void rejectsWrongPassword() {
    assertThat(passwordHasher.verify(
        "wrong-password",
        "0123456789abcdef0123456789abcdef",
        "1aa222f4bc267f2b95a912ba976c0893efb3b43a1e1c0e3ab868a37c8cc90c8a"
            + "8923a4873ea9b4a64e76880ec2d0e0a74d5a2bb9039e99918794c294333d9ddc"
    )).isFalse();
  }
}
