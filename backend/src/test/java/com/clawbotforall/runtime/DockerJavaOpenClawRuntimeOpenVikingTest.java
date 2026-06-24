package com.clawbotforall.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerJavaOpenClawRuntimeOpenVikingTest {

  @Test
  void runnerEnvIncludesOpenVikingTrustedModeConfiguration() {
    OpenVikingEffectiveSettings settings = new OpenVikingEffectiveSettings(
        "http://openviking:8080",
        true,
        "claw-manager",
        "shared-secret",
        "npm:@example/openviking-openclaw-memory@1.0.0",
        "root-key-never-in-runner",
        "broker-token",
        "http://claw-manager-api:8080"
    );

    List<String> env = DockerJavaOpenClawRuntime.runnerEnv(settings, "inst_1");

    assertThat(env).contains(
        "HOME=/var/lib/openclaw",
        "OPENCLAW_HOME=/var/lib/openclaw",
        "OPENCLAW_CONFIG_PATH=/var/lib/openclaw/openclaw.json",
        "OPENCLAW_CONFIG=/var/lib/openclaw/openclaw.json",
        "OPENCLAW_STATE_DIR=/var/lib/openclaw/.openclaw",
        "OPENVIKING_BASE_URL=http://openviking:8080",
        "OPENVIKING_TRUSTED_MODE_ENABLED=true",
        "OPENVIKING_ACCOUNT_ID=claw-manager",
        "OPENVIKING_IDENTITY_HASH_SECRET=shared-secret",
        "CLAW_MANAGER_INTERNAL_BASE_URL=http://claw-manager-api:8080",
        "OPENVIKING_BROKER_TOKEN=broker-token",
        "OPENVIKING_OPENCLAW_INSTANCE_ID=inst_1",
        "OPENVIKING_PLUGIN_PACKAGE=npm:@example/openviking-openclaw-memory@1.0.0"
    );
    assertThat(env).noneMatch(item -> item.contains("root-key-never-in-runner"));
    assertThat(env).noneMatch(item -> item.startsWith("OPENVIKING_ROOT"));
  }

  @Test
  void runnerEnvOmitsOptionalBlankOpenVikingPackageAndBaseUrl() {
    OpenVikingEffectiveSettings settings = new OpenVikingEffectiveSettings(
        "",
        true,
        "claw-manager",
        "shared-secret",
        "",
        "root-key-never-in-runner",
        "broker-token",
        "http://claw-manager-api:8080"
    );

    List<String> env = DockerJavaOpenClawRuntime.runnerEnv(settings, "inst_2");

    assertThat(env).contains(
        "OPENVIKING_TRUSTED_MODE_ENABLED=true",
        "OPENVIKING_ACCOUNT_ID=claw-manager",
        "OPENVIKING_IDENTITY_HASH_SECRET=shared-secret",
        "CLAW_MANAGER_INTERNAL_BASE_URL=http://claw-manager-api:8080",
        "OPENVIKING_BROKER_TOKEN=broker-token",
        "OPENVIKING_OPENCLAW_INSTANCE_ID=inst_2"
    );
    assertThat(env).noneMatch(item -> item.startsWith("OPENVIKING_BASE_URL="));
    assertThat(env).noneMatch(item -> item.startsWith("OPENVIKING_PLUGIN_PACKAGE="));
  }
}
