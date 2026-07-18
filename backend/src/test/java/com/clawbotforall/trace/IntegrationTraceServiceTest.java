package com.clawbotforall.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class IntegrationTraceServiceTest {
  private final IntegrationTraceMapper mapper = mock(IntegrationTraceMapper.class);
  private final IntegrationTraceService service = new IntegrationTraceService(mapper, new ObjectMapper());

  @ParameterizedTest
  @MethodSource("diagnoses")
  void diagnosesFailureAndCompletionTimelines(String expectedCode, List<IntegrationTraceEvent> events) {
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(events);

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo(expectedCode);
  }

  @Test
  void listOrdersEventsChronologicallyInsideEachTrace() {
    when(mapper.listEvents("", "", "", "", "", "", 400, 0)).thenReturn(List.of(
        event("openclaw.dispatch.completed", "completed", "2026-07-16T00:00:03Z"),
        event("openclaw.dispatch.started", "started", "2026-07-16T00:00:01Z")));

    Map<String, Object> result = service.list("", "", "", "", "", "", 1, 20);
    Map<?, ?> summary = (Map<?, ?>) ((List<?>) result.get("items")).getFirst();

    assertThat(summary.get("startedAt")).isEqualTo("2026-07-16T00:00:01Z");
    assertThat(summary.get("finishedAt")).isEqualTo("2026-07-16T00:00:03Z");
    assertThat(summary.get("elapsedMs")).isEqualTo(2000L);
    assertThat(summary.get("lastStage")).isEqualTo("openclaw.dispatch.completed");
  }

  @Test
  void marksCompletedApiStreamAsCompleted() {
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("api.request.received", "completed", "2026-07-16T00:00:00Z", "{\"imageRequested\":false}"),
        event("api.dispatch.started", "started", "2026-07-16T00:00:01Z"),
        event("api.dispatch.completed", "completed", "2026-07-16T00:00:02Z"),
        event("api.stream.completed", "completed", "2026-07-16T00:00:03Z")));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("COMPLETE");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("completed");
  }

  @Test
  void marksCompletedApiImageStreamAsCompletedOnlyAfterArtifactEmission() {
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("api.request.received", "completed", "2026-07-16T00:00:00Z", "{\"imageRequested\":true}"),
        event("api.dispatch.completed", "completed", "2026-07-16T00:00:01Z"),
        event("artifact.html.create.completed", "completed", "2026-07-16T00:00:02Z"),
        event("api.artifact.emitted", "completed", "2026-07-16T00:00:03Z"),
        event("api.stream.completed", "completed", "2026-07-16T00:00:04Z")));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("COMPLETE");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("completed");
  }

  @Test
  void failsApiImageStreamCompletedWithoutArtifactEmission() {
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("api.request.received", "completed", "2026-07-16T00:00:00Z", "{\"imageRequested\":true}"),
        event("api.dispatch.completed", "completed", "2026-07-16T00:00:01Z"),
        event("bridge.image_generate.started", "started", "2026-07-16T00:00:02Z"),
        event("image.provider.request.started", "started", "2026-07-16T00:00:03Z"),
        event("image.file.written", "completed", "2026-07-16T00:00:04Z"),
        event("bridge.tool.started", "started", "2026-07-16T00:00:05Z", "{}", "miniapp_artifact"),
        event("artifact.html.create.completed", "completed", "2026-07-16T00:00:06Z"),
        event("api.stream.completed", "completed", "2026-07-16T00:00:07Z")));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("ARTIFACT_TOOL_FAILED");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("failed");
  }

  @Test
  void marksDispatchWithoutTerminalEventAsTimedOutAfterTenMinutes() {
    String startedAt = Instant.now().minus(11, ChronoUnit.MINUTES).toString();
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("api.request.received", "completed", startedAt),
        event("api.dispatch.started", "started", startedAt)));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("DISPATCH_TIMEOUT");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("failed");
  }

  @Test
  void keepsRecentDispatchWithoutTerminalEventInProgress() {
    String startedAt = Instant.now().minus(1, ChronoUnit.MINUTES).toString();
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("wechat.inbound.received", "completed", startedAt),
        event("openclaw.dispatch.started", "started", startedAt)));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("IN_PROGRESS");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("in_progress");
  }

  @Test
  void marksApiRequestWithoutDispatchAsFailedAfterTenMinutes() {
    String receivedAt = Instant.now().minus(11, ChronoUnit.MINUTES).toString();
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("api.request.received", "completed", receivedAt)));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("NO_OPENCLAW_DISPATCH");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("failed");
  }

  @Test
  void doesNotLetCompletedStreamOverrideDispatchFailure() {
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("api.request.received", "completed", "2026-07-16T00:00:00Z", "{\"imageRequested\":false}"),
        event("api.dispatch.failed", "failed", "2026-07-16T00:00:01Z"),
        event("api.stream.completed", "completed", "2026-07-16T00:00:02Z")));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("FAILED");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("failed");
  }

  @Test
  void diagnosesImageGenerationFailureBeforeIncompleteArtifact() {
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("api.request.received", "completed", "2026-07-16T00:00:00Z", "{\"imageRequested\":true}"),
        event("image.generation.failed", "failed", "2026-07-16T00:00:01Z"),
        event("api.stream.completed", "completed", "2026-07-16T00:00:02Z")));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("IMAGE_TOOL_FAILED");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("failed");
  }

  @Test
  void diagnosesFailedMiniappArtifactToolSeparately() {
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("image.file.written", "completed", "2026-07-16T00:00:00Z"),
        event("bridge.tool.started", "started", "2026-07-16T00:00:01Z", "{}", "miniapp_artifact"),
        event("bridge.tool.failed", "failed", "2026-07-16T00:00:02Z", "{}", "miniapp_artifact")));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("ARTIFACT_TOOL_FAILED");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("failed");
  }

  @Test
  void marksNoImageToolCallAsFailedAfterApiDispatchCompletes() {
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(
        event("api.request.received", "completed", "2026-07-16T00:00:00Z", "{\"imageRequested\":true}"),
        event("api.dispatch.completed", "completed", "2026-07-16T00:00:01Z"),
        event("api.stream.completed", "completed", "2026-07-16T00:00:02Z")));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    assertThat(((Map<?, ?>) detail.get("diagnosis")).get("code")).isEqualTo("NO_IMAGE_TOOL_CALL");
    assertThat(((Map<?, ?>) detail.get("summary")).get("status")).isEqualTo("failed");
  }

  @Test
  void recordWhitelistsDetailsAndRedactsCredentials() {
    service.record(new IntegrationTraceEventRequest(
        "cmtrace_test123", "parent", "wechat-plugin", "wechat.inbound.received", "completed", "wechat",
        "inst_1", "sender_hash", "session_hash", "", "req_1", 200, 200, 12L, "", 
        "Authorization: Bearer secret-token user=cm_user_secret apiKey=sk-super-secret-value",
        Map.of("modelId", "gpt-image-2", "streamMode", "deliver-fallback", "deltaCount", 3, "prompt", "private")), "");

    ArgumentCaptor<IntegrationTraceEvent> captor = forClass(IntegrationTraceEvent.class);
    verify(mapper).insert(captor.capture());
    IntegrationTraceEvent saved = captor.getValue();
    assertThat(saved.errorMessage()).doesNotContain("secret-token", "cm_user_secret", "sk-super-secret-value");
    assertThat(saved.detailJson())
        .contains("modelId", "streamMode", "deliver-fallback", "deltaCount")
        .doesNotContain("prompt");
  }

  @Test
  void detailReturnsArtifactMetadataFromWhitelistedEventDetails() {
    IntegrationTraceEvent artifactEvent = new IntegrationTraceEvent(
        "evt", "cmtrace_test123", "", "claw-manager", "artifact.html.create.completed", "completed", "api",
        "inst_1", "", "", "miniapp_artifact", "req_1", 200, 200, 20L, "", "",
        "{\"imageId\":\"img-1\",\"contentKey\":\"content-1\"}", "2026-07-16T00:00:00Z");
    when(mapper.findByTraceId("cmtrace_test123")).thenReturn(List.of(artifactEvent));

    Map<String, Object> detail = service.detail("cmtrace_test123");

    @SuppressWarnings("unchecked")
    Map<String, Object> artifact = (Map<String, Object>) detail.get("artifact");
    assertThat(artifact)
        .containsEntry("imageId", "img-1")
        .containsEntry("contentKey", "content-1");
  }

  @Test
  void rejectsUnknownComponentOrStage() {
    assertThatThrownBy(() -> service.record(new IntegrationTraceEventRequest(
        "cmtrace_test123", "", "model", "made.up.stage", "completed", "wechat", "", "", "", "", "", null,
        null, null, "", "", Map.of()), ""))
        .isInstanceOf(ApiException.class);
  }

  private static Stream<Arguments> diagnoses() {
    return Stream.of(
        Arguments.of("NO_OPENCLAW_DISPATCH", List.of(event("wechat.inbound.received", "completed", "2026-07-16T00:00:00Z"))),
        Arguments.of("NO_IMAGE_TOOL_CALL", List.of(
            event("wechat.inbound.received", "completed", "2026-07-16T00:00:00Z", "{\"imageRequested\":true}"),
            event("openclaw.dispatch.started", "started", "2026-07-16T00:00:01Z"),
            event("openclaw.dispatch.completed", "completed", "2026-07-16T00:00:02Z"))),
        Arguments.of("IMAGE_TOOL_FAILED", List.of(event("bridge.image_generate.failed", "failed", "2026-07-16T00:00:00Z"))),
        Arguments.of("IMAGE_TOOL_FAILED", List.of(event("image.config.validated", "failed", "2026-07-16T00:00:00Z"))),
        Arguments.of("IMAGE_PROVIDER_NOT_CALLED", List.of(
            event("bridge.image_generate.started", "started", "2026-07-16T00:00:00Z"),
            event("openclaw.dispatch.completed", "completed", "2026-07-16T00:00:01Z"))),
        Arguments.of("IMAGE_PROVIDER_FAILED", List.of(event("image.provider.request.failed", "failed", "2026-07-16T00:00:00Z"))),
        Arguments.of("IMAGE_DECODE_FAILED", List.of(event("image.response.decoded", "failed", "2026-07-16T00:00:00Z"))),
        Arguments.of("IMAGE_FILE_WRITE_FAILED", List.of(event("image.file.written", "failed", "2026-07-16T00:00:00Z"))),
        Arguments.of("ARTIFACT_NOT_CALLED", List.of(
            event("image.file.written", "completed", "2026-07-16T00:00:00Z"),
            event("openclaw.dispatch.completed", "completed", "2026-07-16T00:00:01Z"))),
        Arguments.of("ARTIFACT_UPLOAD_FAILED", List.of(event("artifact.image.upload.failed", "failed", "2026-07-16T00:00:00Z"))),
        Arguments.of("ARTIFACT_HTML_FAILED", List.of(event("artifact.html.create.failed", "failed", "2026-07-16T00:00:00Z"))),
        Arguments.of("WECHAT_MEDIA_FAILED", List.of(event("wechat.media.send.failed", "failed", "2026-07-16T00:00:00Z"))),
        Arguments.of("COMPLETE", List.of(
            event("artifact.html.create.completed", "completed", "2026-07-16T00:00:00Z"),
            event("wechat.media.send.completed", "completed", "2026-07-16T00:00:01Z"),
            event("openclaw.dispatch.completed", "completed", "2026-07-16T00:00:02Z"))),
        Arguments.of("COMPLETE", List.of(
            event("openclaw.dispatch.completed", "completed", "2026-07-16T00:00:00Z"),
            event("wechat.text.send.completed", "completed", "2026-07-16T00:00:01Z")))
    );
  }

  private static IntegrationTraceEvent event(String stage, String status, String createdAt) {
    return event(stage, status, createdAt, "{}");
  }

  private static IntegrationTraceEvent event(String stage, String status, String createdAt, String details) {
    return event(stage, status, createdAt, details, "");
  }

  private static IntegrationTraceEvent event(String stage, String status, String createdAt, String details, String toolName) {
    return new IntegrationTraceEvent("evt", "cmtrace_test123", "", "claw-manager", stage, status, "wechat",
        "inst_1", "sender_hash", "session_hash", toolName, "req_1", null, null, null, "", "", details, createdAt);
  }
}
