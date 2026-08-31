package com.clawbotforall.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HeartbeatSessionScannerTest {
  @Mock InstanceFileService fileService;
  @Mock OpenVikingSettingsService openVikingSettingsService;
  @TempDir Path temp;

  @Test
  void classifiesOnlyStrongHeartbeatEvidenceAndNeverExposesRawIdentifiers() throws Exception {
    Path home = prepareHome();
    Path sessions = agentSessions(home, "user_secret_agent");
    Files.writeString(sessions.resolve("sessions.json"), """
        {
          "agent:user_secret_agent:wechat:peer-secret:heartbeat": {
            "sessionId": "heartbeat-session",
            "heartbeatIsolatedBaseSessionKey": "agent:user_secret_agent:wechat:peer-secret"
          },
          "agent:user_secret_agent:wechat:peer-mixed": {
            "sessionId": "mixed-session",
            "isHeartbeat": true
          },
          "agent:main:wechat:peer-normal": {
            "sessionId": "normal-session"
          }
        }
        """);
    Files.writeString(sessions.resolve("mixed-session.jsonl"),
        "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"internal\"}\n");
    Files.writeString(sessions.resolve("normal-session.jsonl"),
        "{\"role\":\"user\",\"content\":\"HEARTBEAT_OK 2018 -1\"}\n");

    HeartbeatSessionScanner.ScanReport report = scanner(home).scanInstance("instance-secret");

    assertThat(report.findings()).extracting(HeartbeatSessionScanner.SessionFinding::classification)
        .containsExactlyInAnyOrder(
            HeartbeatSessionScanner.Classification.HEARTBEAT_ONLY,
            HeartbeatSessionScanner.Classification.MIXED_PRIMARY,
            HeartbeatSessionScanner.Classification.NORMAL);
    assertThat(report.findings()).allSatisfy(finding -> {
      assertThat(finding.agentIdHash()).startsWith("sha256:");
      assertThat(finding.sessionKeyHash()).startsWith("sha256:");
      assertThat(finding.agentIdHash()).doesNotContain("secret");
      assertThat(finding.sessionKeyHash()).doesNotContain("secret");
    });
    assertThat(report.instanceIdHash()).startsWith("sha256:").doesNotContain("instance-secret");
    assertThat(report.toString()).doesNotContain("peer-secret").doesNotContain("user_secret_agent");
  }

  @Test
  void findsExplicitHeartbeatMetadataInTranscriptButDoesNotGuessFromText() throws Exception {
    Path home = prepareHome();
    Path sessions = agentSessions(home, "agent-a");
    Files.writeString(sessions.resolve("sessions.json"), """
        {
          "agent:agent-a:explicit": {"sessionId": "explicit"},
          "agent:agent-a:text-only": {"sessionId": "text-only"}
        }
        """);
    Files.writeString(sessions.resolve("explicit.jsonl"),
        "{\"runtime\":{\"trigger\":\"heartbeat\"},\"type\":\"message\",\"role\":\"assistant\",\"content\":\"2018\"}\n");
    Files.writeString(sessions.resolve("text-only.jsonl"),
        "{\"role\":\"assistant\",\"content\":\"HEARTBEAT_OK 2018 -1\"}\n");

    HeartbeatSessionScanner.ScanReport report = scanner(home).scanInstance("inst-1");

    assertThat(finding(report, "transcript_heartbeat_marker").classification())
        .isEqualTo(HeartbeatSessionScanner.Classification.MIXED_PRIMARY);
    assertThat(report.findings()).filteredOn(f -> f.classification() == HeartbeatSessionScanner.Classification.NORMAL)
        .hasSize(1);
  }

  @Test
  void protectsSessionsReferencedByRealOpenVikingActiveTurnHmacKey() throws Exception {
    Path home = prepareHome();
    Path sessions = agentSessions(home, "agent-a");
    String sessionKey = "agent:agent-a:active:heartbeat";
    Files.writeString(sessions.resolve("sessions.json"), """
        {"agent:agent-a:active:heartbeat":{"sessionId":"active-heartbeat"}}
        """);
    Path openViking = home.resolve(".openclaw").resolve("openviking");
    Files.createDirectories(openViking);
    String key = hmac32("identity-secret", sessionKey);
    Files.writeString(openViking.resolve("active-turns.json"), """
        {"version":1,"entries":{"%s:turntokenhash":{"channel":"wechat","sessionKeyHash":"%s:turntokenhash","status":"active"}}}
        """.formatted(key, key));

    HeartbeatSessionScanner.ScanReport report = scanner(home).scanInstance("inst-1");

    assertThat(report.findings()).singleElement().satisfies(finding -> {
      assertThat(finding.classification()).isEqualTo(HeartbeatSessionScanner.Classification.ACTIVE_PROTECTED);
      assertThat(finding.evidence()).contains("session_key_suffix", "active_turn_reference");
    });
  }

  @Test
  void senderHandoffDoesNotProtectSessionResetBecauseSessionKeyRemainsStable() throws Exception {
    Path home = prepareHome();
    Path sessions = agentSessions(home, "agent-a");
    String sessionKey = "agent:agent-a:handoff:heartbeat";
    Files.writeString(sessions.resolve("sessions.json"), """
        {"agent:agent-a:handoff:heartbeat":{"sessionId":"handoff-heartbeat","heartbeatIsolatedBaseSessionKey":"agent:agent-a:handoff"}}
        """);
    Files.writeString(sessions.resolve("handoff-heartbeat.jsonl"),
        "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"internal\"}\n");
    Path openViking = home.resolve(".openclaw").resolve("openviking");
    Files.createDirectories(openViking);
    String key = hmac32("identity-secret", sessionKey);
    Files.writeString(openViking.resolve("sender-handoff.json"), """
        {"version":1,"entries":{"%s":{"openVikingUserId":"wx_hash","senderHash":"sender_hash"}}}
        """.formatted(key));

    HeartbeatSessionScanner.ScanReport report = scanner(home).scanInstance("inst-1");

    assertThat(report.findings()).singleElement().satisfies(finding -> {
      assertThat(finding.classification()).isEqualTo(HeartbeatSessionScanner.Classification.HEARTBEAT_ONLY);
      assertThat(finding.evidence()).contains("session_key_suffix").doesNotContain("sender_handoff_reference");
    });
  }

  @Test
  void detailedScanKeepsRawKeysInternalAndDetectsFreshTranscript() throws Exception {
    Path home = prepareHome();
    Path sessions = agentSessions(home, "user_secret_agent");
    Files.writeString(sessions.resolve("sessions.json"), """
        {
          "agent:user_secret_agent:fresh:heartbeat":{"sessionId":"fresh-heartbeat","heartbeatIsolatedBaseSessionKey":"agent:user_secret_agent:fresh"},
          "agent:user_secret_agent:used:heartbeat":{"sessionId":"used-heartbeat","heartbeatIsolatedBaseSessionKey":"agent:user_secret_agent:used"}
        }
        """);
    Files.writeString(sessions.resolve("fresh-heartbeat.jsonl"),
        "{\"type\":\"session\",\"id\":\"fresh-heartbeat\"}\n");
    Files.writeString(sessions.resolve("used-heartbeat.jsonl"),
        "{\"type\":\"session\",\"id\":\"used-heartbeat\"}\n"
            + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"status\"}\n");

    HeartbeatSessionScanner.DetailedScanReport report = scanner(home).scanInstanceDetailed("inst-1");

    assertThat(report.candidates()).hasSize(2);
    assertThat(report.candidates()).filteredOn(candidate -> candidate.sessionKey().contains("fresh"))
        .singleElement().satisfies(candidate -> assertThat(candidate.hasConversationContent()).isFalse());
    assertThat(report.candidates()).filteredOn(candidate -> candidate.sessionKey().contains("used"))
        .singleElement().satisfies(candidate -> assertThat(candidate.hasConversationContent()).isTrue());
    assertThat(report.toPublicReport().toString())
        .doesNotContain("agent:user_secret_agent:fresh:heartbeat")
        .doesNotContain("user_secret_agent");
    assertThat(report.toString())
        .doesNotContain("agent:user_secret_agent:fresh:heartbeat")
        .doesNotContain("user_secret_agent");
  }

  @Test
  void malformedAndOversizedIndexesProduceWarningsWithoutThrowing() throws Exception {
    Path home = prepareHome();
    Path malformed = agentSessions(home, "agent-malformed");
    Files.writeString(malformed.resolve("sessions.json"), "{broken");
    Path oversized = agentSessions(home, "agent-oversized");
    byte[] large = new byte[(int) HeartbeatSessionScanner.MAX_SESSION_INDEX_BYTES + 1];
    Files.write(oversized.resolve("sessions.json"), large);

    HeartbeatSessionScanner.ScanReport report = scanner(home).scanInstance("inst-1");

    assertThat(report.warnings()).contains("session_index_invalid", "session_index_too_large");
    assertThat(report.findings()).isEmpty();
  }

  private HeartbeatSessionScanner.SessionFinding finding(
      HeartbeatSessionScanner.ScanReport report,
      String evidence
  ) {
    return report.findings().stream()
        .filter(value -> value.evidence().contains(evidence))
        .findFirst()
        .orElseThrow();
  }

  private HeartbeatSessionScanner scanner(Path home) {
    when(fileService.paths(anyString())).thenReturn(paths(home));
    when(openVikingSettingsService.effectiveSettings()).thenReturn(new OpenVikingEffectiveSettings(
        "", true, "claw-manager", "identity-secret", "", "", "", ""));
    return new HeartbeatSessionScanner(fileService, new ObjectMapper(), openVikingSettingsService);
  }

  private Path prepareHome() throws Exception {
    Path home = temp.resolve("home");
    Files.createDirectories(home);
    return home;
  }

  private Path agentSessions(Path home, String agentId) throws Exception {
    Path sessions = home.resolve(".openclaw").resolve("agents").resolve(agentId).resolve("sessions");
    Files.createDirectories(sessions);
    return sessions;
  }

  private static String hmac32(String secret, String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
  }

  private InstancePaths paths(Path home) {
    return new InstancePaths(temp, home, temp.resolve("workspace"), temp.resolve("logs"));
  }
}
