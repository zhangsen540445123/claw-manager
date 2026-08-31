package com.clawbotforall.heartbeat;

import com.clawbotforall.heartbeat.HeartbeatSessionScanner.Classification;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.ScanReport;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 在现有 API 进程中执行只读 Heartbeat 状态校准。
 *
 * <p>该任务只扫描，不自动归档、删除或修改 Session，也不修改 Cron。未知和混合会话
 * 继续留给管理员通过查询接口确认，避免后台任务误伤普通对话。</p>
 */
@Service
public class HeartbeatSessionCalibrationScheduler {
  private static final Logger log = LoggerFactory.getLogger(HeartbeatSessionCalibrationScheduler.class);

  private final InstanceAggregateMapper instanceAggregateMapper;
  private final HeartbeatSessionScanner sessionScanner;
  private final CronHeartbeatDependencyScanner cronScanner;
  private final AtomicBoolean running = new AtomicBoolean();

  public HeartbeatSessionCalibrationScheduler(
      InstanceAggregateMapper instanceAggregateMapper,
      HeartbeatSessionScanner sessionScanner,
      CronHeartbeatDependencyScanner cronScanner
  ) {
    this.instanceAggregateMapper = instanceAggregateMapper;
    this.sessionScanner = sessionScanner;
    this.cronScanner = cronScanner;
  }

  /**
   * 首次扫描延迟执行，避免阻塞 Spring ApplicationReadyEvent；之后每五分钟只读校准一次。
   */
  @Scheduled(
      initialDelayString = "${clawbot.heartbeat.initial-scan-delay-ms:10000}",
      fixedDelayString = "${clawbot.heartbeat.scan-delay-ms:300000}"
  )
  public void scanPeriodically() {
    scanAllInstances();
  }

  public void scanAllInstances() {
    if (!running.compareAndSet(false, true)) {
      log.debug("Heartbeat Session 校准跳过：上一次扫描仍在运行");
      return;
    }
    try {
      List<InstanceEntity> instances = instanceAggregateMapper.listAll();
      if (instances == null || instances.isEmpty()) {
        return;
      }
      for (InstanceEntity instance : instances) {
        scanOne(instance);
      }
    } catch (RuntimeException error) {
      // 诊断/校准不能影响 API 启动和其他实例业务。
      log.warn("Heartbeat Session 全量校准失败：errorType={}", error.getClass().getSimpleName());
      log.debug("Heartbeat Session 全量校准异常详情", error);
    } finally {
      running.set(false);
    }
  }

  private void scanOne(InstanceEntity instance) {
    if (instance == null || instance.getId() == null || instance.getId().isBlank()) {
      log.warn("Heartbeat Session 校准跳过：实例 ID 缺失");
      return;
    }
    try {
      ScanReport sessions = sessionScanner.scanInstance(instance.getId());
      CronHeartbeatDependencyScanner.ScanReport cron = cronScanner.scanInstance(instance.getId());
      Map<Classification, Integer> counts = countByClassification(sessions);
      int actionable = counts.getOrDefault(Classification.HEARTBEAT_ONLY, 0)
          + counts.getOrDefault(Classification.MIXED_PRIMARY, 0)
          + counts.getOrDefault(Classification.UNKNOWN, 0)
          + counts.getOrDefault(Classification.ACTIVE_PROTECTED, 0);
      if (actionable > 0 || !sessions.warnings().isEmpty() || !cron.dependencies().isEmpty()
          || !cron.warnings().isEmpty()) {
        log.info(
            "Heartbeat Session 校准完成：instanceHash={} heartbeatOnly={} mixed={} unknown={} "
                + "activeProtected={} cronNextHeartbeat={} warnings={}",
            sessions.instanceIdHash(),
            counts.getOrDefault(Classification.HEARTBEAT_ONLY, 0),
            counts.getOrDefault(Classification.MIXED_PRIMARY, 0),
            counts.getOrDefault(Classification.UNKNOWN, 0),
            counts.getOrDefault(Classification.ACTIVE_PROTECTED, 0),
            cron.dependencies().size(),
            sessions.warnings().size() + cron.warnings().size());
      } else {
        log.debug("Heartbeat Session 校准完成：instanceHash={} status=clean", sessions.instanceIdHash());
      }
    } catch (RuntimeException error) {
      log.warn(
          "Heartbeat Session 校准失败：instanceHash={} errorType={}",
          safeHash(instance.getId()), error.getClass().getSimpleName());
      log.debug("Heartbeat Session 校准异常详情：instanceHash={}", safeHash(instance.getId()), error);
    }
  }

  private static Map<Classification, Integer> countByClassification(ScanReport report) {
    EnumMap<Classification, Integer> counts = new EnumMap<>(Classification.class);
    if (report == null || report.findings() == null) {
      return counts;
    }
    for (HeartbeatSessionScanner.SessionFinding finding : report.findings()) {
      if (finding != null && finding.classification() != null) {
        counts.merge(finding.classification(), 1, Integer::sum);
      }
    }
    return counts;
  }

  private static String safeHash(String instanceId) {
    return com.clawbotforall.wechat.WechatLogSanitizer.identityHashPreview(instanceId);
  }
}
