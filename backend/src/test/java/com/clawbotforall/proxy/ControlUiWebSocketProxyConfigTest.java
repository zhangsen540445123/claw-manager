package com.clawbotforall.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ControlUiWebSocketProxyConfigTest {

  @Test
  void ignoresPlainHttpProxyRequests() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy/inst_1/");
    request.setServletPath("/proxy/inst_1/");

    assertThat(ControlUiWebSocketProxyConfig.UpgradeOnlyHandlerMapping.isWebSocketUpgrade(request)).isFalse();
    assertThat(mapping().getHandler(request)).isNull();
  }

  @Test
  void handlesWebSocketUpgradeRequests() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy/inst_1/ws");
    request.setServletPath("/proxy/inst_1/ws");
    request.addHeader("Upgrade", "websocket");
    request.addHeader("Connection", "keep-alive, Upgrade");
    request.addHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");

    assertThat(ControlUiWebSocketProxyConfig.UpgradeOnlyHandlerMapping.isWebSocketUpgrade(request)).isTrue();
    assertThat(mapping().getHandler(request)).isNotNull();
  }

  private static ControlUiWebSocketProxyConfig.UpgradeOnlyHandlerMapping mapping() {
    ControlUiWebSocketProxyConfig.UpgradeOnlyHandlerMapping mapping =
        new ControlUiWebSocketProxyConfig.UpgradeOnlyHandlerMapping();
    mapping.setUrlMap(Map.of("/proxy/**", new Object()));
    mapping.setOrder(-1);
    return mapping;
  }
}
