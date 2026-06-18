package com.clawbotforall.runtime;

/**
 * 运行时报告的当前容器状态。
 */
public record RuntimeState(
    boolean running,
    String status,
    String startedAt
) {
  public static RuntimeState stopped() {
    return new RuntimeState(false, "stopped", null);
  }
}
