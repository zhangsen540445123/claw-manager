package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.runtime.ProxyTarget;
import org.junit.jupiter.api.Test;

class InstanceProvisioningServiceTest {

  @Test
  void buildsGatewayHealthUriFromResolvedRuntimeTarget() {
    ProxyTarget target = new ProxyTarget(
        "clawbot-openclaw-demo",
        18789,
        "container-network",
        "claw-manager_default"
    );

    assertThat(InstanceProvisioningService.gatewayReadyUri(target))
        .hasToString("http://clawbot-openclaw-demo:18789/");
  }
}
