package com.clawbotforall.diagnostics;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时为所有运行中的 OpenClaw 实例采集 OOM 诊断数据。 */
@Component
public class OomDiagnosticsScheduler {

  private static final Logger log = LoggerFactory.getLogger(OomDiagnosticsScheduler.class);
  private static final Executor COLLECTION_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "openclaw-oom-diagnostics");
    thread.setDaemon(true);
    return thread;
  });

  private final InstanceAggregateMapper instanceAggregateMapper;
  private final OomDiagnosticsService diagnosticsService;
  private final ClawbotProperties properties;
  private final Executor collectionExecutor;
  private final AtomicBoolean collectionInProgress = new AtomicBoolean(false);

  public OomDiagnosticsScheduler(
      InstanceAggregateMapper instanceAggregateMapper,
      OomDiagnosticsService diagnosticsService,
      ClawbotProperties properties
  ) {
    this(instanceAggregateMapper, diagnosticsService, properties, COLLECTION_EXECUTOR);
  }

  OomDiagnosticsScheduler(
      InstanceAggregateMapper instanceAggregateMapper,
      OomDiagnosticsService diagnosticsService,
      ClawbotProperties properties,
      Executor collectionExecutor
  ) {
    this.instanceAggregateMapper = instanceAggregateMapper;
    this.diagnosticsService = diagnosticsService;
    this.properties = properties;
    this.collectionExecutor = collectionExecutor;
  }

  @Scheduled(fixedDelayString = "${clawbot.oom-diagnostics.interval-ms:30000}")
  public void collectRuntimeDiagnostics() {
    if (!properties.oomDiagnostics().enabled() || !collectionInProgress.compareAndSet(false, true)) {
      return;
    }
    try {
      collectionExecutor.execute(this::collectAllRuntimeInstances);
    } catch (Throwable error) {
      collectionInProgress.set(false);
      log.warn("提交 OOM 诊断采集任务失败：errorType={}", error.getClass().getSimpleName());
    }
  }

  private void collectAllRuntimeInstances() {
    try {
      List<InstanceEntity> instances;
      try {
        instances = instanceAggregateMapper.listRuntimeActive();
        if (instances == null) {
          instances = List.of();
        }
      } catch (Throwable error) {
        log.warn("读取 OOM 诊断实例列表失败：errorType={}", error.getClass().getSimpleName());
        return;
      }
      for (InstanceEntity instance : instances) {
        if (instance == null || !properties.oomDiagnostics().collectionEnabledFor(instance.getId())) {
          continue;
        }
        try {
          diagnosticsService.collect(instance);
        } catch (Throwable error) {
          log.warn(
              "单实例 OOM 诊断调度失败：instanceId={}, errorType={}",
              instance == null ? "unknown" : instance.getId(),
              error.getClass().getSimpleName()
          );
        }
      }
    } finally {
      collectionInProgress.set(false);
    }
  }
}
