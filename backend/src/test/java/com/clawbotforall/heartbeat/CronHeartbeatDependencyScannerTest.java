package com.clawbotforall.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CronHeartbeatDependencyScannerTest {
  @Mock InstanceFileService fileService;
  @TempDir Path temp;

  @Test
  void reportsOnlyEnabledNextHeartbeatJobsWithoutExposingJobDetails() throws Exception {
    Path home = prepareHome();
    Path cron = home.resolve(".openclaw").resolve("cron");
    Files.createDirectories(cron);
    Files.writeString(cron.resolve("jobs.json"), """
        {
          "jobs": [
            {
              "id": "cron-secret-active",
              "name": "send private report",
              "enabled": true,
              "wakeMode": "next-heartbeat",
              "payload": {"text": "private-message"}
            },
            {
              "id": "cron-disabled",
              "enabled": false,
              "wakeMode": "next-heartbeat"
            },
            {
              "id": "cron-immediate",
              "enabled": true,
              "wakeMode": "now"
            }
          ]
        }
        """);

    CronHeartbeatDependencyScanner.ScanReport report = scanner(home).scanInstance("instance-secret");

    assertThat(report.dependencies()).singleElement().satisfies(dependency -> {
      assertThat(dependency.jobIdHash()).startsWith("sha256:");
      assertThat(dependency.jobIdHash()).doesNotContain("cron-secret-active");
      assertThat(dependency.enabled()).isTrue();
      assertThat(dependency.wakeMode()).isEqualTo("next-heartbeat");
    });
    assertThat(report.instanceIdHash()).startsWith("sha256:").doesNotContain("instance-secret");
    assertThat(report.toString()).doesNotContain("private-message").doesNotContain("send private report");
  }

  @Test
  void supportsTopLevelJobArraysAndTreatsMissingEnabledAsDisabled() throws Exception {
    Path home = prepareHome();
    Path cron = home.resolve(".openclaw").resolve("cron");
    Files.createDirectories(cron);
    Files.writeString(cron.resolve("jobs.json"), """
        [
          {"id":"explicit-enabled","enabled":true,"wakeMode":"NEXT-HEARTBEAT"},
          {"id":"missing-enabled","wakeMode":"next-heartbeat"}
        ]
        """);

    CronHeartbeatDependencyScanner.ScanReport report = scanner(home).scanInstance("inst-1");

    assertThat(report.dependencies()).hasSize(1);
    assertThat(report.dependencies().getFirst().wakeMode()).isEqualTo("next-heartbeat");
  }

  @Test
  void malformedAndOversizedJobFilesProduceWarningsWithoutThrowing() throws Exception {
    Path home = prepareHome();
    Path cron = home.resolve(".openclaw").resolve("cron");
    Files.createDirectories(cron);
    Files.writeString(cron.resolve("jobs.json"), "{broken");

    CronHeartbeatDependencyScanner.ScanReport malformed = scanner(home).scanInstance("inst-1");
    assertThat(malformed.warnings()).contains("cron_jobs_invalid");

    Files.write(cron.resolve("jobs.json"), new byte[(int) CronHeartbeatDependencyScanner.MAX_JOBS_FILE_BYTES + 1]);
    CronHeartbeatDependencyScanner.ScanReport oversized = scanner(home).scanInstance("inst-1");
    assertThat(oversized.warnings()).contains("cron_jobs_too_large");
  }

  @Test
  void missingCronFileReturnsEmptyReport() throws Exception {
    CronHeartbeatDependencyScanner.ScanReport report = scanner(prepareHome()).scanInstance("inst-1");

    assertThat(report.dependencies()).isEmpty();
    assertThat(report.warnings()).isEmpty();
  }

  private CronHeartbeatDependencyScanner scanner(Path home) {
    when(fileService.paths(anyString())).thenReturn(paths(home));
    return new CronHeartbeatDependencyScanner(fileService, new ObjectMapper());
  }

  private Path prepareHome() throws Exception {
    Path home = temp.resolve("home");
    Files.createDirectories(home);
    return home;
  }

  private InstancePaths paths(Path home) {
    return new InstancePaths(temp, home, temp.resolve("workspace"), temp.resolve("logs"));
  }
}
