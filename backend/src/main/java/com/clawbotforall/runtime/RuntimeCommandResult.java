package com.clawbotforall.runtime;

/** 受限的容器内只读命令结果，供诊断等非业务用途使用。 */
public record RuntimeCommandResult(
    String output,
    int exitCode,
    boolean timedOut,
    Throwable error
) {
  public boolean succeeded() {
    return !timedOut && error == null && exitCode == 0;
  }
}
