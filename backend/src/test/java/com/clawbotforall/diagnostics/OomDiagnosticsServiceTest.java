package com.clawbotforall.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.InstanceStats;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.ProxyTarget;
import com.clawbotforall.runtime.RunnerImageStatus;
import com.clawbotforall.runtime.RuntimeCommandResult;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OomDiagnosticsServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void writesBoundedMetricsForAnEnabledInstanceWithoutSensitiveCommandOutput() throws Exception {
    StubRuntime runtime = new StubRuntime();
    OomDiagnosticsService service = new OomDiagnosticsService(properties(true), runtime, new ObjectMapper());
    InstanceEntity instance = instance("inst-1");

    service.collect(instance);

    Path dir = tempDir.resolve("instances/inst-1/home/diagnostics/oom");
    assertThat(dir.resolve("memory-latest.json")).exists();
    String latest = Files.readString(dir.resolve("memory-latest.json"));
    assertThat(latest).contains("\"memoryPercent\" : \"85.00%\"");
    assertThat(latest).contains("\"nodePid\" : 42");
    assertThat(latest).contains("\"rssAnonKib\" : 700000");
    assertThat(latest).contains("\"processes\"");
    assertThat(latest).doesNotContain("private-message", "secret-token", "accountId", "peerId");
    assertThat(Files.list(dir).filter(path -> path.getFileName().toString().startsWith("metrics-")).count())
        .isEqualTo(1);
  }

  @Test
  void doesNothingWhenCollectionSwitchIsDisabled() {
    OomDiagnosticsService service = new OomDiagnosticsService(properties(false), new StubRuntime(), new ObjectMapper());

    service.collect(instance("inst-1"));

    assertThat(tempDir.resolve("instances/inst-1/home/diagnostics/oom")).doesNotExist();
  }

  @Test
  void parsesOnlyWhitelistedDiagnosticKeys() {
    Map<String, Object> parsed = OomDiagnosticsService.parseProcessSample("""
        node_pid=42
        node_cmd_match=1
        snapshot_signal_ready=1
        vm_rss_kib=800000
        rss_anon_kib=700000
        process=42|1|node|800000
        process=19|1|document-worker|120000
        prompt=private-message
        authorization=secret-token
        """);

    assertThat(parsed).containsEntry("nodePid", 42L).containsEntry("rssAnonKib", 700000L);
    assertThat((List<?>) parsed.get("processes")).hasSize(2);
    assertThat(parsed).doesNotContainKeys("prompt", "authorization");
  }


  @Test
  void heapSnapshotEligibilityRequiresSelectedInstanceThresholdAndPreparedNode() {
    ClawbotProperties.OomDiagnostics config = properties(true).oomDiagnostics();
    Map<String, Object> readySample = Map.<String, Object>of(
        "nodePid", 42L,
        "nodeCommandMatched", true,
        "snapshotSignalReady", true
    );

    assertThat(OomDiagnosticsService.shouldScheduleHeapSnapshot(
        config,
        "inst-1",
        new InstanceStats("1%", "1.7GiB / 2GiB", "85%", "0B / 0B", "10"),
        readySample
    )).isTrue();
    assertThat(OomDiagnosticsService.shouldScheduleHeapSnapshot(
        config,
        "inst-1",
        new InstanceStats("1%", "1.0GiB / 2GiB", "50%", "0B / 0B", "10"),
        readySample
    )).isFalse();
    assertThat(OomDiagnosticsService.shouldScheduleHeapSnapshot(
        config,
        "inst-2",
        new InstanceStats("1%", "1.7GiB / 2GiB", "85%", "0B / 0B", "10"),
        readySample
    )).isFalse();
    assertThat(OomDiagnosticsService.shouldScheduleHeapSnapshot(
        config,
        "inst-1",
        new InstanceStats("1%", "1.7GiB / 2GiB", "85%", "0B / 0B", "10"),
        Map.<String, Object>of("nodePid", 42L, "nodeCommandMatched", false, "snapshotSignalReady", true)
    )).isFalse();
  }
  private ClawbotProperties properties(boolean enabled) {
    return new ClawbotProperties(
        new ClawbotProperties.Paths(tempDir.toString()),
        null,
        null,
        null,
        new ClawbotProperties.OomDiagnostics(
            enabled, 30_000, 7, 256, 1, List.of("inst-1"), 5, 12, 1, 600_000
        )
    );
  }

  private static InstanceEntity instance(String id) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId(id);
    instance.setContainerName("container-" + id);
    instance.setStatus("running");
    return instance;
  }

  private static final class StubRuntime implements OpenClawRuntime {
    @Override public RuntimeState startInstance(InstanceEntity instance, InstancePaths paths) { return new RuntimeState(true, "running", null); }
    @Override public RuntimeState stopInstance(InstanceEntity instance) { return RuntimeState.stopped(); }
    @Override public RuntimeState inspectInstance(InstanceEntity instance) { return new RuntimeState(true, "running", null); }
    @Override public String getLogs(InstanceEntity instance, int tail) { return ""; }
    @Override public InstanceStats getStats(InstanceEntity instance) {
      return new InstanceStats("2.00%", "1.70GiB / 2GiB", "85.00%", "1MB / 2MB", "15");
    }
    @Override public ProxyTarget resolveProxyTarget(InstanceEntity instance) { return null; }
    @Override public RunnerImageStatus getRunnerImageStatus() { return null; }
    @Override public RunnerImageStatus refreshRunnerImage() { return null; }
    @Override public RuntimeExecHandle startExec(InstanceEntity instance, String command, long timeoutMs, Map<String, String> env, RuntimeExecListener listener) { throw new UnsupportedOperationException(); }
    @Override public RuntimeExecHandle startExec(InstanceEntity instance, List<String> command, long timeoutMs, Map<String, String> env, RuntimeExecListener listener) { throw new UnsupportedOperationException(); }
    @Override public RuntimeCommandResult executeReadOnly(InstanceEntity instance, List<String> command, long timeoutMs, int maxOutputChars) {
      return new RuntimeCommandResult("""
          node_pid=42
          node_cmd_match=1
          snapshot_signal_ready=0
          vm_rss_kib=800000
          rss_anon_kib=700000
          rss_file_kib=100000
          vm_swap_kib=0
          pss_kib=790000
          private_dirty_kib=680000
          anonymous_kib=700000
          cgroup_memory_current_bytes=1825361100
          cgroup_memory_swap_current_bytes=0
          cgroup_event_oom=0
          cgroup_event_oom_kill=0
          process=42|1|node|800000
          process=19|1|document-worker|120000
          ignored=private-message
          """, 0, false, null);
    }
  }
}
