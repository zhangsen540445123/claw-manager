package com.clawbotforall.runtime;

import com.clawbotforall.instance.InstanceEntity;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理 OpenClaw 容器和命令的运行时抽象。
 */
public interface OpenClawRuntime {

  /**
   * 确保实例容器存在，并挂载指定文件系统路径后处于运行状态。
   */
  RuntimeState startInstance(InstanceEntity instance, InstancePaths paths);

  /**
   * 在实例容器存在时停止该容器。
   */
  RuntimeState stopInstance(InstanceEntity instance);

  /**
   * 读取当前容器状态且不修改容器。
   */
  RuntimeState inspectInstance(InstanceEntity instance);

  /**
   * 读取受限数量的最近容器日志行。
   */
  String getLogs(InstanceEntity instance, int tail);

  /**
   * 读取当前容器资源使用快照。
   */
  InstanceStats getStats(InstanceEntity instance);

  /**
   * 解析 Control UI 代理使用的后端地址。
   */
  ProxyTarget resolveProxyTarget(InstanceEntity instance);

  /**
   * 返回当前本地 OpenClaw 运行镜像状态。
   */
  RunnerImageStatus getRunnerImageStatus();

  /**
   * 刷新 OpenClaw 运行镜像并返回最新状态。
   */
  RunnerImageStatus refreshRunnerImage();

  /**
   * 在实例容器内启动命令，不附加额外环境变量。
   */
  default RuntimeExecHandle startExec(
      InstanceEntity instance,
      String command,
      long timeoutMs,
      RuntimeExecListener listener
  ) {
    return startExec(instance, command, timeoutMs, Map.of(), listener);
  }

  /**
   * 在实例容器内启动命令，并向监听器流式回调生命周期事件。
   */
  RuntimeExecHandle startExec(
      InstanceEntity instance,
      String command,
      long timeoutMs,
      Map<String, String> env,
      RuntimeExecListener listener
  );

  /**
   * 在实例容器内以 argv 形式启动命令，不经过 shell 解析。
   */
  default RuntimeExecHandle startExec(
      InstanceEntity instance,
      List<String> command,
      long timeoutMs,
      RuntimeExecListener listener
  ) {
    return startExec(instance, command, timeoutMs, Map.of(), listener);
  }

  /**
   * 在实例容器内以 argv 形式启动命令，并向监听器流式回调生命周期事件。
   */
  RuntimeExecHandle startExec(
      InstanceEntity instance,
      List<String> command,
      long timeoutMs,
      Map<String, String> env,
      RuntimeExecListener listener
  );

  /**
   * 执行有界的非交互式命令并收集有限输出。用于诊断采样，失败不会向业务线程抛出。
   */
  default RuntimeCommandResult executeReadOnly(
      InstanceEntity instance,
      List<String> command,
      long timeoutMs,
      int maxOutputChars
  ) {
    if (command == null || command.isEmpty() || maxOutputChars < 1) {
      return new RuntimeCommandResult("", -1, false, new IllegalArgumentException("诊断命令参数无效。"));
    }
    StringBuilder output = new StringBuilder(Math.min(maxOutputChars, 4096));
    CountDownLatch completed = new CountDownLatch(1);
    AtomicBoolean timedOut = new AtomicBoolean(false);
    AtomicBoolean terminal = new AtomicBoolean(false);
    RuntimeExecHandle[] handle = new RuntimeExecHandle[1];
    Throwable[] error = new Throwable[1];
    int[] exitCode = new int[] {-1};
    try {
      handle[0] = startExec(instance, command, Math.max(1, timeoutMs), new RuntimeExecListener() {
        @Override
        public void onOutput(String chunk) {
          if (chunk == null || terminal.get()) {
            return;
          }
          synchronized (output) {
            if (output.length() < maxOutputChars) {
              int remaining = maxOutputChars - output.length();
              output.append(chunk, 0, Math.min(remaining, chunk.length()));
            }
          }
        }

        @Override
        public void onComplete(int code) {
          if (terminal.compareAndSet(false, true)) {
            exitCode[0] = code;
            completed.countDown();
          }
        }

        @Override
        public void onTimeout() {
          if (terminal.compareAndSet(false, true)) {
            timedOut.set(true);
            completed.countDown();
          }
        }

        @Override
        public void onError(Throwable throwable) {
          if (terminal.compareAndSet(false, true)) {
            error[0] = throwable;
            completed.countDown();
          }
        }
      });
      if (!completed.await(Math.max(1, timeoutMs) + 2_000, TimeUnit.MILLISECONDS)
          && terminal.compareAndSet(false, true)) {
        timedOut.set(true);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      timedOut.set(true);
      error[0] = interrupted;
    } catch (Throwable throwable) {
      error[0] = throwable;
    } finally {
      if ((timedOut.get() || error[0] != null) && handle[0] != null) {
        try {
          handle[0].cancel();
        } catch (Throwable ignored) {
          // 诊断清理失败不影响业务。
        }
      }
    }
    return new RuntimeCommandResult(output.toString(), exitCode[0], timedOut.get(), error[0]);
  }
}
