package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WechatLogSanitizerTest {

  @Test
  void hashesIdentityWithoutRetainingRawWechatUserId() {
    String raw = "o9cq805zYxJ9dUBkeCRtXhCiSQro@im.wechat";

    String sanitized = WechatLogSanitizer.identityHashPreview(raw);

    assertThat(sanitized).matches("sha256:[0-9a-f]{12}");
    assertThat(sanitized).doesNotContain(raw);
  }

  @Test
  void presenceValueDoesNotRevealTokenOrIdentity() {
    assertThat(WechatLogSanitizer.present("token_secret_value")).isEqualTo("present");
    assertThat(WechatLogSanitizer.present(" ")).isEqualTo("absent");
  }
}
