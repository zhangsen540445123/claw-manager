package com.clawbotforall.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelCallAuditServiceTest {
  private final ModelCallAuditMapper mapper = mock(ModelCallAuditMapper.class);
  private final ModelCallAuditService service = new ModelCallAuditService(mapper, new ObjectMapper(), true, 30);

  @Test
  void recordsFullInputWithoutLoggingIt() {
    ModelCallAuditEventRequest request = new ModelCallAuditEventRequest(
        "llm_input", "inst_1", "agent-main", "session-1", "agent:agent-main:session-1",
        "run-1", "call-1", "openai", "gpt-5.6", "system 中文", "<relevant-memories>记忆</relevant-memories>",
        List.of(Map.of("role", "user", "content", "你好")), 0, null, null, null, null, null, null,
        Instant.parse("2026-09-01T00:00:00Z"));

    service.record(request);

    var captor = org.mockito.ArgumentCaptor.forClass(ModelCallAudit.class);
    verify(mapper).insert(captor.capture());
    ModelCallAudit saved = captor.getValue();
    assertThat(saved.instanceId()).isEqualTo("inst_1");
    assertThat(saved.sessionKeyHash()).isNotBlank().doesNotContain("agent:agent-main");
    assertThat(saved.systemPrompt()).contains("system 中文");
    assertThat(saved.prompt()).contains("relevant-memories");
    assertThat(saved.historyMessagesJson()).contains("你好");
    assertThat(saved.expiresAt()).isEqualTo("2026-10-01T00:00:00Z");
  }

  @Test
  void disabledAuditDoesNotPersist() {
    ModelCallAuditService disabled = new ModelCallAuditService(mapper, new ObjectMapper(), false, 30);
    disabled.record(new ModelCallAuditEventRequest("llm_input", "inst", null, null, null, "run", null,
        "p", "m", null, "prompt", List.of(), 0, null, null, null, null, null, null));
    org.mockito.Mockito.verifyNoInteractions(mapper);
  }

  @Test
  void detailReturnsAuditsForTraceWindowWithClockSkewAllowance() {
    when(mapper.findForTrace("inst_1", "session-hash", "2026-08-31T23:59:30Z", "2026-09-01T00:01:30Z"))
        .thenReturn(List.of(new ModelCallAudit("id", "llm_input", "inst_1", "agent", "session", "session-hash",
            "run", "call", "provider", "model", "chat/http", "2026.6.39", "system", "prompt", "[]", 0, null, null, null, null, null,
            null, "2026-09-01T00:00:30Z", "2026-10-01T00:00:30Z")));

    var result = service.forTrace("inst_1", "session-hash", "2026-09-01T00:00:00Z", "2026-09-01T00:01:00Z");

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().eventType()).isEqualTo("llm_input");
  }

  @Test
  void detailFallsBackToInstanceWindowWhenTraceHashDoesNotMatchAuditHash() {
    when(mapper.findForTrace("inst_1", "trace-session-hash", "2026-08-31T23:59:30Z", "2026-09-01T00:01:30Z"))
        .thenReturn(List.of());
    when(mapper.findForTrace("inst_1", "", "2026-08-31T23:59:30Z", "2026-09-01T00:01:30Z"))
        .thenReturn(List.of(new ModelCallAudit("id", "llm_input", "inst_1", "agent", "session", "audit-session-hash",
            "run", "call", "provider", "model", "chat/http", "2026.6.39", "system", "prompt", "[]", 0, null, null, null, null, null,
            null, "2026-09-01T00:00:30Z", "2026-10-01T00:00:30Z")));

    var result = service.forTrace("inst_1", "trace-session-hash", "2026-09-01T00:00:00Z", "2026-09-01T00:01:00Z");

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().sessionKeyHash()).isEqualTo("audit-session-hash");
  }
}
