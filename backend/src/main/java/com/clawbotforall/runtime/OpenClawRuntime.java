package com.clawbotforall.runtime;

import com.clawbotforall.instance.InstanceEntity;
import java.util.List;
import java.util.Map;

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
}
