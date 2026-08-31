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
    Runtime runtime
) {
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
          List.of("*"),
          false,
          "30m",
          true,
          true,
          "block"
      );
    }
  }

  public record Paths(String dataDir) {}

  public record Admin(String email, String name, String password) {}

  public record Security(String sessionCookieName, int sessionTtlDays) {}

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
      List<String> controlUiAllowedOrigins,
      boolean agentHeartbeatEnabled,
      String agentHeartbeatEvery,
      boolean agentHeartbeatIsolatedSession,
      boolean agentHeartbeatLightContext,
      String agentHeartbeatDirectPolicy
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
          gatewayReadyCheckIntervalMs, gatewayReadyProbeTimeoutMs, controlUiAllowedOrigins,
          false, "30m", true, true, "block"
      );
    }

    public Runtime {
      if (runnerMemorySwap == null) {
        runnerMemorySwap = "";
      }
      if (controlUiAllowedOrigins == null) {
        controlUiAllowedOrigins = List.of();
      }
      agentHeartbeatEvery = normalizeHeartbeatEvery(agentHeartbeatEvery);
      if (!agentHeartbeatIsolatedSession) {
        throw new IllegalArgumentException("OpenClaw Agent Heartbeat 必须使用独立 Session。");
      }
      agentHeartbeatDirectPolicy = agentHeartbeatDirectPolicy == null
          ? "block"
          : agentHeartbeatDirectPolicy.trim().toLowerCase(java.util.Locale.ROOT);
      if (!"block".equals(agentHeartbeatDirectPolicy)) {
        throw new IllegalArgumentException("OpenClaw Agent Heartbeat directPolicy 只允许 block。");
      }
    }

    private static String normalizeHeartbeatEvery(String value) {
      String normalized = value == null ? "30m" : value.trim().toLowerCase(java.util.Locale.ROOT);
      if (!normalized.matches("[1-9][0-9]*(ms|s|m|h|d)")) {
        throw new IllegalArgumentException("OPENCLAW_AGENT_HEARTBEAT_EVERY 格式无效。");
      }
      return normalized;
    }
  }
}
