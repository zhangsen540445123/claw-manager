package com.clawbotforall.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
          "1.0",
          "1g",
          600_000,
          120_000,
          1_800_000,
          10_000,
          5_000,
          1_000_000,
          128_000,
          List.of(
              "http://localhost:4300",
              "http://127.0.0.1:4300",
              "http://localhost:14300",
              "http://127.0.0.1:14300"
          )
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
      long wechatBindTimeoutMs,
      long wechatQrTtlMs,
      long gatewayReadyTimeoutMs,
      long gatewayReadyCheckIntervalMs,
      long gatewayReadyProbeTimeoutMs,
      int modelContextWindow,
      int modelMaxTokens,
      List<String> controlUiAllowedOrigins
  ) {
    public Runtime {
      if (modelContextWindow <= 0) {
        modelContextWindow = 1_000_000;
      }
      if (modelMaxTokens <= 0) {
        modelMaxTokens = 128_000;
      }
      if (controlUiAllowedOrigins == null) {
        controlUiAllowedOrigins = List.of();
      }
    }
  }
}
