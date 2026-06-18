package com.clawbotforall.runtime;

/**
 * 运行时命令执行结果的流式回调接口。
 */
public interface RuntimeExecListener {

  /**
   * 接收运行中命令的流式输出。
   */
  void onOutput(String output);

  /**
   * 命令正常完成后接收最终进程退出码。
   */
  void onComplete(int exitCode);

  /**
   * 接收命令执行超时通知。
   */
  void onTimeout();

  /**
   * 接收命令执行过程中的异常失败。
   */
  void onError(Throwable error);
}
