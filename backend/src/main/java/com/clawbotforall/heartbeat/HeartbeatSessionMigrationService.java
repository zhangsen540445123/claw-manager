package com.clawbotforall.heartbeat;

import com.clawbotforall.heartbeat.HeartbeatSessionScanner.Classification;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.DetailedScanReport;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.DetailedSessionCandidate;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.wechat.OpenClawGatewayRpcService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 使用 OpenClaw 官方 {@code sessions.reset} RPC 安全轮换已确认的 Heartbeat 污染 Session。
 *
 * <p>该服务不直接修改 Session 索引或 transcript 文件。每次执行前重新扫描，且同一实例串行处理。</p>
 */
@Service
public class HeartbeatSessionMigrationService {
  private static final Logger log = LoggerFactory.getLogger(HeartbeatSessionMigrationService.class);
  private static final int MAX_REQUESTED_SESSION_HASHES = 100;

  private final HeartbeatSessionScanner scanner;
  private final OpenClawGatewayRpcService gatewayRpcService;
  private final ConcurrentHashMap<String, ReentrantLock> instanceLocks = new ConcurrentHashMap<>();

  public HeartbeatSessionMigrationService(
      HeartbeatSessionScanner scanner,
      OpenClawGatewayRpcService gatewayRpcService
  ) {
    this.scanner = scanner;
    this.gatewayRpcService = gatewayRpcService;
  }

  public ResetReport resetSessions(InstanceEntity instance, List<String> sessionKeyHashes) {
    Objects.requireNonNull(instance, "instance");
    String instanceId = normalize(instance.getId());
    if (instanceId.isBlank()) {
      throw new IllegalArgumentException("实例 ID 不能为空。");
    }

    ReentrantLock lock = instanceLocks.computeIfAbsent(instanceId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      DetailedScanReport scan = scanner.scanInstanceDetailed(instanceId);
      List<SessionResetResult> results = new ArrayList<>();
      RequestedHashes requested = normalizeRequestedHashes(sessionKeyHashes);
      for (String requestedHash : requested.values()) {
        results.add(resetOne(instance, scan, requestedHash));
      }
      List<String> warnings = new ArrayList<>(scan.warnings());
      if (requested.truncated()) {
        warnings.add("session_hash_limit_reached");
      }
      return new ResetReport(scan.instanceIdHash(), results, warnings);
    } finally {
      lock.unlock();
    }
  }

  private SessionResetResult resetOne(
      InstanceEntity instance,
      DetailedScanReport scan,
      String sessionKeyHash
  ) {
    if (sessionKeyHash.isBlank()) {
      return result("absent", null, ResetStatus.REJECTED, "session_hash_empty");
    }

    List<DetailedSessionCandidate> matches = scan.candidates().stream()
        .filter(candidate -> sessionKeyHash.equals(candidate.finding().sessionKeyHash()))
        .toList();
    if (matches.isEmpty()) {
      return result(sessionKeyHash, null, ResetStatus.SKIPPED,
          "session_not_found_or_already_rotated");
    }
    if (matches.size() > 1) {
      return result(sessionKeyHash, null, ResetStatus.REJECTED, "session_hash_ambiguous");
    }

    DetailedSessionCandidate candidate = matches.getFirst();
    Classification classification = candidate.finding().classification();
    if (classification == Classification.ACTIVE_PROTECTED) {
      return result(sessionKeyHash, classification, ResetStatus.REJECTED,
          "active_session_protected");
    }
    if (classification == Classification.UNKNOWN) {
      return result(sessionKeyHash, classification, ResetStatus.REJECTED,
          "session_classification_unknown");
    }
    if (classification == Classification.MIXED_PRIMARY) {
      return result(sessionKeyHash, classification, ResetStatus.REJECTED,
          "mixed_session_requires_manual_new");
    }
    if (classification == Classification.NORMAL) {
      return result(sessionKeyHash, classification, ResetStatus.SKIPPED,
          "normal_session_not_selected");
    }
    if (classification == Classification.HEARTBEAT_ONLY
        && !candidate.hasConversationContent()) {
      return result(sessionKeyHash, classification, ResetStatus.SKIPPED,
          "fresh_heartbeat_session");
    }

    try {
      gatewayRpcService.resetSession(instance, candidate.sessionKey());
      log.info(
          "Heartbeat 污染 Session 已通过官方 RPC 轮换: instanceHash={} sessionHash={} classification={}",
          scan.instanceIdHash(), sessionKeyHash, classification);
      return result(sessionKeyHash, classification, ResetStatus.RESET, null);
    } catch (RuntimeException error) {
      log.warn(
          "Heartbeat 污染 Session 轮换失败: instanceHash={} sessionHash={} classification={} errorType={}",
          scan.instanceIdHash(), sessionKeyHash, classification, error.getClass().getSimpleName());
      return result(sessionKeyHash, classification, ResetStatus.FAILED, "session_reset_failed");
    }
  }

  private static SessionResetResult result(
      String sessionKeyHash,
      Classification classification,
      ResetStatus status,
      String warning
  ) {
    return new SessionResetResult(sessionKeyHash, classification, status, warning);
  }

  private static RequestedHashes normalizeRequestedHashes(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return new RequestedHashes(List.of(), false);
    }
    java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
    boolean truncated = false;
    for (String value : requested) {
      String normalized = normalize(value);
      if (unique.contains(normalized)) {
        continue;
      }
      if (unique.size() >= MAX_REQUESTED_SESSION_HASHES) {
        truncated = true;
        break;
      }
      unique.add(normalized);
    }
    return new RequestedHashes(List.copyOf(unique), truncated);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private record RequestedHashes(List<String> values, boolean truncated) {}

  public enum ResetStatus {
    RESET,
    SKIPPED,
    REJECTED,
    FAILED
  }

  public record SessionResetResult(
      String sessionKeyHash,
      Classification classification,
      ResetStatus status,
      String warning
  ) {}

  public record ResetReport(
      String instanceIdHash,
      List<SessionResetResult> results,
      List<String> warnings
  ) {
    public ResetReport {
      results = results == null ? List.of() : List.copyOf(results);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }
}
