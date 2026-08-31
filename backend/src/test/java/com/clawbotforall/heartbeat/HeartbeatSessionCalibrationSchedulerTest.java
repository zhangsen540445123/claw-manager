package com.clawbotforall.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HeartbeatSessionCalibrationSchedulerTest {
  @Mock InstanceAggregateMapper instanceAggregateMapper;
  @Mock HeartbeatSessionScanner sessionScanner;
  @Mock CronHeartbeatDependencyScanner cronScanner;

  @Test
  void scansAllInstancesAndKeepsCronSeparateWithoutMutatingAnything() {
    InstanceEntity first = instance("inst-1");
    InstanceEntity second = instance("inst-2");
    when(instanceAggregateMapper.listAll()).thenReturn(List.of(first, second));
    when(sessionScanner.scanInstance("inst-1")).thenReturn(report("hash-1", HeartbeatSessionScanner.Classification.NORMAL));
    when(sessionScanner.scanInstance("inst-2")).thenReturn(report("hash-2", HeartbeatSessionScanner.Classification.UNKNOWN));
    when(cronScanner.scanInstance("inst-1")).thenReturn(cronReport());
    when(cronScanner.scanInstance("inst-2")).thenReturn(cronReport());

    HeartbeatSessionCalibrationScheduler scheduler = scheduler();
    scheduler.scanAllInstances();

    verify(sessionScanner).scanInstance("inst-1");
    verify(sessionScanner).scanInstance("inst-2");
    verify(cronScanner).scanInstance("inst-1");
    verify(cronScanner).scanInstance("inst-2");
  }

  @Test
  void continuesScanningOtherInstancesWhenOneInstanceFails() {
    InstanceEntity first = instance("inst-1");
    InstanceEntity second = instance("inst-2");
    when(instanceAggregateMapper.listAll()).thenReturn(List.of(first, second));
    doThrow(new IllegalStateException("should not escape")).when(sessionScanner).scanInstance("inst-1");
    when(sessionScanner.scanInstance("inst-2")).thenReturn(report("hash-2", HeartbeatSessionScanner.Classification.NORMAL));
    when(cronScanner.scanInstance("inst-2")).thenReturn(cronReport());

    scheduler().scanAllInstances();

    verify(sessionScanner).scanInstance("inst-2");
    verify(cronScanner).scanInstance("inst-2");
  }

  @Test
  void emptyInstanceListDoesNotScanOrMutate() {
    when(instanceAggregateMapper.listAll()).thenReturn(List.of());

    scheduler().scanAllInstances();

    verifyNoInteractions(sessionScanner, cronScanner);
  }

  @Test
  void nullInstanceIdsAreSkipped() {
    InstanceEntity missing = instance(null);
    when(instanceAggregateMapper.listAll()).thenReturn(List.of(missing));

    scheduler().scanAllInstances();

    verifyNoInteractions(sessionScanner, cronScanner);
  }

  private HeartbeatSessionCalibrationScheduler scheduler() {
    return new HeartbeatSessionCalibrationScheduler(instanceAggregateMapper, sessionScanner, cronScanner);
  }

  private static InstanceEntity instance(String id) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId(id);
    return instance;
  }

  private static HeartbeatSessionScanner.ScanReport report(
      String instanceHash,
      HeartbeatSessionScanner.Classification classification
  ) {
    return new HeartbeatSessionScanner.ScanReport(
        instanceHash,
        List.of(new HeartbeatSessionScanner.SessionFinding("agent-hash", "session-hash", classification, List.of())),
        List.of());
  }

  private static CronHeartbeatDependencyScanner.ScanReport cronReport() {
    return new CronHeartbeatDependencyScanner.ScanReport("instance-hash", List.of(), List.of());
  }
}
