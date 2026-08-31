package com.clawbotforall.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.heartbeat.HeartbeatSessionMigrationService.ResetReport;
import com.clawbotforall.heartbeat.HeartbeatSessionMigrationService.ResetStatus;
import com.clawbotforall.heartbeat.HeartbeatSessionMigrationService.SessionResetResult;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.Classification;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.ScanReport;
import com.clawbotforall.heartbeat.HeartbeatSessionScanner.SessionFinding;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class HeartbeatSessionControllerTest {
  @Mock InstanceCommandService instanceCommandService;
  @Mock HeartbeatSessionScanner scanner;
  @Mock HeartbeatSessionMigrationService migrationService;

  private HeartbeatSessionController controller;
  private InstanceEntity instance;

  @BeforeEach
  void setUp() {
    controller = new HeartbeatSessionController(instanceCommandService, scanner, migrationService);
    instance = new InstanceEntity();
    instance.setId("inst-1");
  }

  @Test
  void scansSessionsForAuthenticatedAdminAfterValidatingInstance() {
    ScanReport report = new ScanReport("instance-hash", List.of(
        new SessionFinding("agent-hash", "session-hash", Classification.MIXED_PRIMARY,
            List.of("transcript_heartbeat_marker"))), List.of());
    when(instanceCommandService.requireInstance("inst-1")).thenReturn(instance);
    when(scanner.scanInstance("inst-1")).thenReturn(report);

    Map<String, Object> response = controller.scan("inst-1", authentication());

    verify(instanceCommandService).requireInstance("inst-1");
    assertThat(response.get("report")).isEqualTo(report);
  }

  @Test
  void resetsOnlyAfterExplicitConfirmation() {
    ResetReport report = new ResetReport("instance-hash", List.of(
        new SessionResetResult("session-hash", Classification.MIXED_PRIMARY,
            ResetStatus.RESET, null)), List.of());
    when(instanceCommandService.requireInstance("inst-1")).thenReturn(instance);
    when(migrationService.resetSessions(instance, List.of("session-hash"))).thenReturn(report);

    Map<String, Object> response = controller.reset(
        "inst-1",
        new HeartbeatSessionController.ResetSessionsRequest(List.of("session-hash"), true),
        authentication());

    assertThat(response.get("report")).isEqualTo(report);
    verify(migrationService).resetSessions(instance, List.of("session-hash"));
  }

  @Test
  void rejectsMissingConfirmationBeforeLookingUpInstance() {
    var request = new HeartbeatSessionController.ResetSessionsRequest(
        List.of("session-hash"), false);

    assertThatThrownBy(() -> controller.reset("inst-1", request, authentication()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("确认");
    verify(instanceCommandService, never()).requireInstance("inst-1");
    verify(migrationService, never()).resetSessions(instance, List.of("session-hash"));
  }

  @Test
  void rejectsEmptySessionHashes() {
    var request = new HeartbeatSessionController.ResetSessionsRequest(List.of(), true);

    assertThatThrownBy(() -> controller.reset("inst-1", request, authentication()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Session");
    verify(instanceCommandService, never()).requireInstance("inst-1");
  }

  @Test
  void rejectsAnonymousAccessForScanAndReset() {
    assertThatThrownBy(() -> controller.scan("inst-1", null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("请先登录");
    assertThatThrownBy(() -> controller.reset(
        "inst-1",
        new HeartbeatSessionController.ResetSessionsRequest(List.of("session-hash"), true),
        null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("请先登录");
    verify(instanceCommandService, never()).requireInstance("inst-1");
  }

  private static TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new AuthenticatedAdmin("admin-1", "admin@example.test", "Admin", false, "now", "now"),
        null);
  }
}
