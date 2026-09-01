package com.clawbotforall.trace;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import com.clawbotforall.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCallAuditControllerTest {
  @Test
  void internalAuditRequiresBrokerToken() {
    ModelCallAuditService service = mock(ModelCallAuditService.class);
    OpenVikingBrokerTokenService tokens = mock(OpenVikingBrokerTokenService.class);
    ModelCallAuditController controller = new ModelCallAuditController(service, tokens);
    when(tokens.matches("bad")).thenReturn(false);

    assertThatThrownBy(() -> controller.event(new ModelCallAuditEventRequest("llm_input", "inst", null, null, null,
        "run", null, "p", "m", null, "prompt", List.of(), 0, null, null, null, null, null, null), "Bearer bad"))
        .isInstanceOf(ApiException.class).hasMessageContaining("token 无效");
  }
}
