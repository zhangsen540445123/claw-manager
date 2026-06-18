package com.clawbotforall.instance;

import com.clawbotforall.runtime.InstanceStats;
import com.clawbotforall.runtime.OpenClawRuntime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期发布活跃实例的运行统计。
 */
@Component
public class InstanceStatsScheduler {

  private final InstanceAggregateMapper instanceAggregateMapper;
  private final OpenClawRuntime openClawRuntime;
  private final InstanceEventPublisher eventPublisher;

  public InstanceStatsScheduler(
      InstanceAggregateMapper instanceAggregateMapper,
      OpenClawRuntime openClawRuntime,
      InstanceEventPublisher eventPublisher
  ) {
    this.instanceAggregateMapper = instanceAggregateMapper;
    this.openClawRuntime = openClawRuntime;
    this.eventPublisher = eventPublisher;
  }

  /**
   * 定时读取运行中实例的资源统计并推送给前端。
   */

  @Scheduled(fixedDelayString = "${clawbot.runtime.stats-poll-interval-ms:5000}")
  public void publishRuntimeStats() {
    List<InstanceEntity> instances = instanceAggregateMapper.listRuntimeActive();
    for (InstanceEntity instance : instances) {
      InstanceStats stats = openClawRuntime.getStats(instance);
      if (stats != null) {
        eventPublisher.publishStatsUpdated(instance.getId(), stats);
      }
    }
  }
}
