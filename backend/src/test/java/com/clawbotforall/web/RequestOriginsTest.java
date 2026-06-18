package com.clawbotforall.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestOriginsTest {

  @Test
  void resolvesForwardedHostWithPort() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-Proto", "http");
    request.addHeader("X-Forwarded-Host", "127.0.0.1:4300");
    request.addHeader("Host", "api:8080");

    assertThat(RequestOrigins.resolve(request)).isEqualTo("http://127.0.0.1:4300");
  }

  @Test
  void fallsBackToHostHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Host", "127.0.0.1:8080");

    assertThat(RequestOrigins.resolve(request)).isEqualTo("http://127.0.0.1:8080");
  }
}
