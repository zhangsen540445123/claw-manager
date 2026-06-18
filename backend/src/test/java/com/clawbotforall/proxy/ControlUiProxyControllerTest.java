package com.clawbotforall.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.runtime.ProxyTarget;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ControlUiProxyControllerTest {

  @Test
  void preservesProxyPathAndQueryString() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy/inst_1/assets/app.js");
    request.setQueryString("v=1");

    assertThat(ControlUiProxyController.targetUri(
        "inst_1",
        request,
        new ProxyTarget("openclaw", 18789, "container-network", "clawbot_default")
    )).hasToString("http://openclaw:18789/assets/app.js?v=1");
  }

  @Test
  void proxiesInstanceRootToGatewayRoot() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy/inst_1");

    assertThat(ControlUiProxyController.targetUri(
        "inst_1",
        request,
        new ProxyTarget("127.0.0.1", 19001, "published-port", "")
    )).hasToString("http://127.0.0.1:19001/");
  }

  @Test
  void buildsWebSocketProxyTargetUri() {
    assertThat(ProxyUris.wsTargetUri(
        "inst_1",
        java.net.URI.create("ws://localhost:8080/proxy/inst_1/socket/live?token=abc"),
        new ProxyTarget("openclaw", 18789, "container-network", "clawbot_default")
    )).hasToString("ws://openclaw:18789/socket/live?token=abc");
  }
}
