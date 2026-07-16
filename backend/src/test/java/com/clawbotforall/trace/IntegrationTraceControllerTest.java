package com.clawbotforall.trace;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class IntegrationTraceControllerTest {
  @Test
  void internalEventRequiresBrokerToken() {
    IntegrationTraceService service = mock(IntegrationTraceService.class);
    OpenVikingBrokerTokenService tokens = mock(OpenVikingBrokerTokenService.class);
    IntegrationTraceController controller = new IntegrationTraceController(service, tokens);
    when(tokens.matches("bad")).thenReturn(false);

    assertThatThrownBy(() -> controller.event(request(), "Bearer bad", "cmtrace_test123"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("token 无效");
  }

  @Test
  void adminListPassesAllFiltersToService() {
    IntegrationTraceService service = mock(IntegrationTraceService.class);
    OpenVikingBrokerTokenService tokens = mock(OpenVikingBrokerTokenService.class);
    IntegrationTraceController controller = new IntegrationTraceController(service, tokens);
    when(service.list("inst_1", "wechat", "failed", "wechat-plugin", "wechat.media.send.failed",
        "WECHAT_MEDIA_FAILED", "from", "to", 2, 50)).thenReturn(Map.of("items", java.util.List.of()));

    controller.list("inst_1", "wechat", "failed", "wechat-plugin", "wechat.media.send.failed",
        "WECHAT_MEDIA_FAILED", "from", "to", 2, 50, authentication());

    verify(service).list("inst_1", "wechat", "failed", "wechat-plugin", "wechat.media.send.failed",
        "WECHAT_MEDIA_FAILED", "from", "to", 2, 50);
  }

  @Test
  void adminDetailRejectsMissingLogin() {
    IntegrationTraceController controller = new IntegrationTraceController(
        mock(IntegrationTraceService.class), mock(OpenVikingBrokerTokenService.class));

    assertThatThrownBy(() -> controller.detail("cmtrace_test123", null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("请先登录");
  }

  private static IntegrationTraceEventRequest request() {
    return new IntegrationTraceEventRequest("cmtrace_test123", "", "wechat-plugin", "wechat.inbound.received",
        "completed", "wechat", "inst_1", "", "", "", "req_1", null, null, null, "", "", Map.of());
  }

  private static TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(new AuthenticatedAdmin(
        "admin_1", "admin@example.test", "Admin", false, "2026-07-16T00:00:00Z", "2026-07-16T00:00:00Z"), null);
  }
}
