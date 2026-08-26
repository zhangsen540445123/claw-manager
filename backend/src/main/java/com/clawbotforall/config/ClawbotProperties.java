package com.clawbotforall.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 绑定应用配置命名空间下的后端配置属性。
 */
@ConfigurationProperties(prefix = "clawbot")
public record ClawbotProperties(
    Paths paths,
    Admin admin,
    Security security,
    Runtime runtime,
    OomDiagnostics oomDiagnostics
) {
  public ClawbotProperties(Paths paths, Admin admin, Security security, Runtime runtime) {
    this(paths, admin, security, runtime, null);
  }

  @ConstructorBinding
  public ClawbotProperties {
    if (paths == null) {
      paths = new Paths("data");
    }
    if (security == null) {
      security = new Security("clawbot_session", 14);
    }
    if (admin == null) {
      admin = new Admin("", "平台管理员", "");
    }
    if (runtime == null) {
      runtime = new Runtime(
          "ghcr.io/zhangsen540445123/claw-manager-openclaw-runner:latest",
          600_000,
          "1",
          "2g",
          "4g",
          1536,
          600_000,
          120_000,
          1_800_000,
          10_000,
          5_000,
          List.of("*")
      );
    }
    if (oomDiagnostics == null) {
      oomDiagnostics = OomDiagnostics.defaults();
    }
  }

  public record Paths(String dataDir) {}

  public record Admin(String email, String name, String password) {}

  public record Security(String sessionCookieName, int sessionTtlDays) {}

  public record OomDiagnostics(
      boolean enabled,
      long intervalMs,
      int retentionDays,
      long metricsLimitMib,
      long minFreeDiskGib,
      List<String> heapSnapshotInstanceIds,
      int heapSnapshotMaxCount,
      long heapSnapshotMaxTotalGib,
      int heapSnapshotPerInstanceMaxCount,
      long heapSnapshotMinIntervalMs
  ) {
    public OomDiagnostics {
      intervalMs = intervalMs > 0 ? intervalMs : 30_000;
      retentionDays = retentionDays > 0 ? retentionDays : 7;
      metricsLimitMib = metricsLimitMib > 0 ? metricsLimitMib : 256;
      minFreeDiskGib = minFreeDiskGib > 0 ? minFreeDiskGib : 30;
      heapSnapshotInstanceIds = heapSnapshotInstanceIds == null
          ? List.of()
          : heapSnapshotInstanceIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
      heapSnapshotMaxCount = heapSnapshotMaxCount > 0 ? heapSnapshotMaxCount : 5;
      heapSnapshotMaxTotalGib = heapSnapshotMaxTotalGib > 0 ? heapSnapshotMaxTotalGib : 12;
      heapSnapshotPerInstanceMaxCount = heapSnapshotPerInstanceMaxCount > 0 ? heapSnapshotPerInstanceMaxCount : 1;
      heapSnapshotMinIntervalMs = heapSnapshotMinIntervalMs > 0 ? heapSnapshotMinIntervalMs : 600_000;
    }

    public static OomDiagnostics defaults() {
      return new OomDiagnostics(false, 30_000, 7, 256, 30, List.of(), 5, 12, 1, 600_000);
    }

    public boolean collectionEnabledFor(String instanceId) {
      return enabled
          && instanceId != null
          && (heapSnapshotInstanceIds.isEmpty() || heapSnapshotInstanceIds.contains(instanceId));
    }

    public boolean snapshotEnabledFor(String instanceId) {
      return collectionEnabledFor(instanceId);
    }
  }

  public record Runtime(
      String runnerImage,
      long runnerPullTimeoutMs,
      String runnerCpus,
      String runnerMemory,
      String runnerMemorySwap,
      int runnerNodeMaxOldSpaceMb,
      long wechatBindTimeoutMs,
      long wechatQrTtlMs,
      long gatewayReadyTimeoutMs,
      long gatewayReadyCheckIntervalMs,
      long gatewayReadyProbeTimeoutMs,
      List<String> controlUiAllowedOrigins
  ) {
    public Runtime(
        String runnerImage,
        long runnerPullTimeoutMs,
        String runnerCpus,
        String runnerMemory,
        long wechatBindTimeoutMs,
        long wechatQrTtlMs,
        long gatewayReadyTimeoutMs,
        long gatewayReadyCheckIntervalMs,
        long gatewayReadyProbeTimeoutMs,
        List<String> controlUiAllowedOrigins
    ) {
      this(
          runnerImage, runnerPullTimeoutMs, runnerCpus, runnerMemory, "", 0,
          wechatBindTimeoutMs, wechatQrTtlMs, gatewayReadyTimeoutMs,
          gatewayReadyCheckIntervalMs, gatewayReadyProbeTimeoutMs, controlUiAllowedOrigins
      );
    }

    public Runtime {
      if (runnerMemorySwap == null) {
        runnerMemorySwap = "";
      }
      if (controlUiAllowedOrigins == null) {
        controlUiAllowedOrigins = List.of();
      }
    }
  }
}
