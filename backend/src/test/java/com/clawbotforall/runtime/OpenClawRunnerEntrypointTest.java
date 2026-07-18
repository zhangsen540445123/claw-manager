package com.clawbotforall.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenClawRunnerEntrypointTest {

  @Test
  void entrypointStartsGatewayWithoutInstallingManagedPlugins() throws Exception {
    String script = Files.readString(Path.of("..", "containers", "openclaw-runner", "entrypoint.sh"));

    assertThat(script).doesNotContain("OPENVIKING_PLUGIN_PACKAGE");
    assertThat(script).doesNotContain("openclaw plugins install");
    assertThat(script).contains("exec openclaw gateway");
  }
}
