package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.model.ModelProviderDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelAuthServiceTest {

  @Test
  void infersDeviceCodeAuthUrl() {
    ModelProviderDefinition definition = new ModelProviderDefinition(
        "github-copilot",
        "GitHub Copilot",
        "github-copilot",
        "device_code",
        "github-copilot",
        "device",
        "",
        "",
        "",
        true,
        false,
        List.of()
    );

    ModelAuthService.ModelAuthState state = ModelAuthService.inferModelAuthState(
        "Open https://github.com/login/device and enter code ABCD-EFGH",
        definition,
        null
    );

    assertThat(state.status()).isEqualTo("running");
    assertThat(state.authUrl()).isEqualTo("https://github.com/login/device");
    assertThat(state.message()).contains("浏览器完成授权");
    assertThat(state.needsInput()).isFalse();
  }

  @Test
  void infersPromptInputState() {
    ModelProviderDefinition definition = new ModelProviderDefinition(
        "anthropic-setup-token",
        "Anthropic",
        "anthropic",
        "oauth",
        "anthropic",
        "setup-token",
        "",
        "",
        "",
        true,
        false,
        List.of()
    );

    ModelAuthService.ModelAuthState state = ModelAuthService.inferModelAuthState(
        "Paste the redirect URL below\n◆ Redirect URL",
        definition,
        null
    );

    assertThat(state.status()).isEqualTo("waiting_input");
    assertThat(state.promptLabel()).isEqualTo("Redirect URL");
    assertThat(state.needsInput()).isTrue();
  }

  @Test
  void keepsOnlyRecentOutputSnippet() {
    String longOutput = "x".repeat(5000);

    ModelAuthService.ModelAuthState state = ModelAuthService.inferModelAuthState(
        longOutput,
        null,
        null
    );

    assertThat(state.outputSnippet()).hasSize(4000);
  }
}
