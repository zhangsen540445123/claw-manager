package com.clawbotforall.runtime;

/**
 * OpenClaw 运行时内部命令的执行句柄。
 */
public interface RuntimeExecHandle {

  /**
   * 向运行中命令的标准输入流发送内容。
   */
  void sendInput(String input);

  /**
   * 请求取消运行中的命令。
   */
  void cancel();

  /**
   * 报告是否已经请求取消。
   */
  boolean isCancelled();
}
