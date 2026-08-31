package com.clawbotforall.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.heartbeat.HeartbeatSessionMigrationService.ResetStatus;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.Classification;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.DetailedScanReport;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.DetailedSessionCandidate;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.SessionFinding;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.wechat.OpenClawGatewayRpcService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HeartbeatSessionMigrationServiceTest {
  @Mock HeartbeatSessionScanner scanner;
  @Mock OpenClawGatewayRpcService gatewayRpcService;

  private HeartbeatSessionMigrationService service;
  private InstanceEntity instance;

  @BeforeEach
  void setUp() {
    service = new HeartbeatSessionMigrationService(scanner, gatewayRpcService);
    instance = new InstanceEntity();
    instance.setId("inst-secret-1");
  }

  @Test
  void resetsOnlyConfirmedHeartbeatSessionsAndRejectsMixedSessions() {
    when(scanner.scanInstanceDetailed("inst-secret-1")).thenReturn(report(
        candidate("agent-a", "session-heartbeat", "hash-heartbeat", Classification.HEARTBEAT_ONLY, true),
        candidate("agent-b", "session-mixed", "hash-mixed", Classification.MIXED_PRIMARY, true),
        candidate("agent-c", "session-fresh", "hash-fresh", Classification.HEARTBEAT_ONLY, false),
        candidate("agent-d", "session-normal", "hash-normal", Classification.NORMAL, true)
    ));

    var result = service.resetSessions(instance,
        List.of("hash-heartbeat", "hash-mixed", "hash-fresh", "hash-normal"));

    verify(gatewayRpcService).resetSession(instance, "session-heartbeat");
    verify(gatewayRpcService, never()).resetSession(instance, "session-mixed");
    verify(gatewayRpcService, never()).resetSession(instance, "session-fresh");
    verify(gatewayRpcService, never()).resetSession(instance, "session-normal");
    assertThat(result.results()).extracting(HeartbeatSessionMigrationService.SessionResetResult::status)
        .containsExactly(ResetStatus.RESET, ResetStatus.REJECTED, ResetStatus.SKIPPED, ResetStatus.SKIPPED);
    assertThat(result.results().get(1).warning()).isEqualTo("mixed_session_requires_manual_new");
  }

  @Test
  void rejectsActiveUnknownAndAmbiguousCandidatesWithoutCallingGateway() {
    when(scanner.scanInstanceDetailed("inst-secret-1")).thenReturn(report(
        candidate("agent-a", "session-active", "hash-active", Classification.ACTIVE_PROTECTED, true),
        candidate("agent-b", "session-unknown", "hash-unknown", Classification.UNKNOWN, true),
        candidate("agent-c", "session-one", "hash-duplicate", Classification.HEARTBEAT_ONLY, true),
        candidate("agent-d", "session-two", "hash-duplicate", Classification.MIXED_PRIMARY, true)
    ));

    var result = service.resetSessions(instance,
        List.of("hash-active", "hash-unknown", "hash-duplicate", " "));

    verify(gatewayRpcService, never()).resetSession(any(), any());
    assertThat(result.results()).extracting(HeartbeatSessionMigrationService.SessionResetResult::status)
        .containsOnly(ResetStatus.REJECTED);
    assertThat(result.results()).extracting(HeartbeatSessionMigrationService.SessionResetResult::warning)
        .containsExactly(
            "active_session_protected",
            "session_classification_unknown",
            "session_hash_ambiguous",
            "session_hash_empty");
  }

  @Test
  void skipsMissingHashAsAlreadyRotated() {
    when(scanner.scanInstanceDetailed("inst-secret-1")).thenReturn(report());

    var result = service.resetSessions(instance, List.of("hash-gone"));

    assertThat(result.results()).singleElement().satisfies(item -> {
      assertThat(item.status()).isEqualTo(ResetStatus.SKIPPED);
      assertThat(item.warning()).isEqualTo("session_not_found_or_already_rotated");
    });
  }

  @Test
  void continuesAfterOneGatewayResetFailsAndDoesNotExposeFailureDetails() {
    when(scanner.scanInstanceDetailed("inst-secret-1")).thenReturn(report(
        candidate("agent-a", "session-secret-one", "hash-one", Classification.HEARTBEAT_ONLY, true),
        candidate("agent-b", "session-secret-two", "hash-two", Classification.HEARTBEAT_ONLY, true)
    ));
    doThrow(new IllegalStateException("token=secret C:\\private\\session-secret-one.jsonl"))
        .when(gatewayRpcService).resetSession(instance, "session-secret-one");

    var result = service.resetSessions(instance, List.of("hash-one", "hash-two"));

    verify(gatewayRpcService).resetSession(instance, "session-secret-two");
    assertThat(result.results()).extracting(HeartbeatSessionMigrationService.SessionResetResult::status)
        .containsExactly(ResetStatus.FAILED, ResetStatus.RESET);
    assertThat(result.toString())
        .doesNotContain("session-secret-one")
        .doesNotContain("token=secret")
        .doesNotContain("C:\\private");
  }

  @Test
  void trimsAndDeduplicatesHashesAndLimitsTheRequest() {
    List<DetailedSessionCandidate> candidates = java.util.stream.IntStream.range(0, 101)
        .mapToObj(index -> candidate("agent-" + index, "session-" + index,
            "hash-" + index, Classification.NORMAL, true))
        .toList();
    when(scanner.scanInstanceDetailed("inst-secret-1")).thenReturn(report(
        candidates.toArray(DetailedSessionCandidate[]::new)));

    var requested = new java.util.ArrayList<String>();
    requested.add(" hash-0 ");
    requested.add("hash-0");
    requested.add(" ");
    for (int index = 1; index <= 100; index++) {
      requested.add("hash-" + index);
    }

    var result = service.resetSessions(instance, requested);

    assertThat(result.results()).hasSize(100);
    assertThat(result.results().get(0).sessionKeyHash()).isEqualTo("hash-0");
    assertThat(result.results().get(1).warning()).isEqualTo("session_hash_empty");
    assertThat(result.results()).filteredOn(item -> "hash-0".equals(item.sessionKeyHash())).hasSize(1);
    assertThat(result.results()).anySatisfy(item -> assertThat(item.sessionKeyHash()).isEqualTo("hash-98"));
    assertThat(result.results()).noneSatisfy(item -> assertThat(item.sessionKeyHash()).isEqualTo("hash-99"));
    assertThat(result.warnings()).contains("session_hash_limit_reached");
    verify(gatewayRpcService, never()).resetSession(any(), any());
  }

  @Test
  void returnsOnlyPublicHashesAndScannerWarnings() {
    when(scanner.scanInstanceDetailed("inst-secret-1")).thenReturn(new DetailedScanReport(
        "instance-public-hash",
        List.of(candidate("agent-private", "session-private", "session-public-hash",
            Classification.HEARTBEAT_ONLY, true)),
        List.of("scan_truncated")));

    var result = service.resetSessions(instance, List.of("session-public-hash"));

    assertThat(result.instanceIdHash()).isEqualTo("instance-public-hash");
    assertThat(result.warnings()).containsExactly("scan_truncated");
    assertThat(result.toString())
        .contains("session-public-hash")
        .doesNotContain("agent-private")
        .doesNotContain("session-private")
        .doesNotContain("inst-secret-1");
  }

  @Test
  void serializesResetOperationsForTheSameInstance() throws Exception {
    DetailedScanReport scan = report(
        candidate("agent-a", "session-one", "hash-one", Classification.HEARTBEAT_ONLY, true));
    when(scanner.scanInstanceDetailed("inst-secret-1")).thenReturn(scan);
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger concurrentCalls = new AtomicInteger();
    AtomicInteger maxConcurrentCalls = new AtomicInteger();
    doAnswer(invocation -> {
      int current = concurrentCalls.incrementAndGet();
      maxConcurrentCalls.accumulateAndGet(current, Math::max);
      firstEntered.countDown();
      releaseFirst.await(5, TimeUnit.SECONDS);
      concurrentCalls.decrementAndGet();
      return null;
    }).when(gatewayRpcService).resetSession(instance, "session-one");

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> service.resetSessions(instance, List.of("hash-one")));
      assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> second = executor.submit(() -> service.resetSessions(instance, List.of("hash-one")));
      Thread.sleep(150);
      assertThat(maxConcurrentCalls.get()).isEqualTo(1);
      releaseFirst.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
      assertThat(maxConcurrentCalls.get()).isEqualTo(1);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  private static DetailedScanReport report(DetailedSessionCandidate... candidates) {
    return new DetailedScanReport("instance-hash", List.of(candidates), List.of());
  }

  private static DetailedSessionCandidate candidate(
      String agentId,
      String sessionKey,
      String sessionHash,
      Classification classification,
      boolean hasConversationContent
  ) {
    return new DetailedSessionCandidate(
        agentId,
        sessionKey,
        new SessionFinding("agent-hash", sessionHash, classification, List.of("test")),
        hasConversationContent);
  }
}
