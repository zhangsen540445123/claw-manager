package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class OpenClawUserDataCleanerTest {
  @TempDir Path tempDir;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void deletesOnlyValidatedAgentDirectoriesAndMatchingStateEntries() throws Exception {
    InstanceFileService files = Mockito.mock(InstanceFileService.class);
    Path home = tempDir.resolve("home");
    Path state = home.resolve(".openclaw");
    String oldAgent = "user_11111111111111111111111111111111";
    Files.createDirectories(state.resolve("agents").resolve(oldAgent).resolve("sessions"));
    Files.writeString(state.resolve("agents").resolve(oldAgent).resolve("sessions").resolve("sessions.json"), "{}");
    Files.createDirectories(state.resolve("workspace-" + oldAgent));
    Files.writeString(state.resolve("workspace-" + oldAgent).resolve("MEMORY.md"), "secret");
    Files.createDirectories(state.resolve("openviking"));
    Files.writeString(state.resolve("openviking").resolve("active-turns.json"), """
        {"version":1,"entries":{"old":{"agentId":"%s","sessionId":"session-old"},"keep":{"agentId":"user_22222222222222222222222222222222"}}}
        """.formatted(oldAgent));
    Files.writeString(state.resolve("openviking").resolve("sender-handoff.json"), """
        {"version":1,"entries":{"old-api":{"openVikingUserId":"wx_memory","senderHash":"deadbeef"},"keep":{"openVikingUserId":"wx_other","senderHash":"keep"}}}
        """);
    when(files.paths("inst_1")).thenReturn(new InstancePaths(tempDir, home, tempDir.resolve("workspace"), tempDir.resolve("logs")));

    OpenClawUserDataCleaner cleaner = new OpenClawUserDataCleaner(files, objectMapper);
    cleaner.deleteOldUserData("inst_1", oldAgent, List.of("session-old"), List.of("api:deadbeef"));

    assertThat(Files.exists(state.resolve("agents").resolve(oldAgent))).isFalse();
    assertThat(Files.exists(state.resolve("workspace-" + oldAgent))).isFalse();
    JsonNode turns = objectMapper.readTree(state.resolve("openviking").resolve("active-turns.json").toFile());
    assertThat(turns.path("entries").has("old")).isFalse();
    assertThat(turns.path("entries").has("keep")).isTrue();
    JsonNode handoff = objectMapper.readTree(state.resolve("openviking").resolve("sender-handoff.json").toFile());
    assertThat(handoff.path("entries").has("old-api")).isFalse();
    assertThat(handoff.path("entries").has("keep")).isTrue();
  }

  @Test
  void readsSessionIdsBeforeDeletingAgentDirectory() throws Exception {
    InstanceFileService files = Mockito.mock(InstanceFileService.class);
    Path home = tempDir.resolve("home");
    Path sessions = home.resolve(".openclaw/agents/user_11111111111111111111111111111111/sessions");
    Files.createDirectories(sessions);
    Files.writeString(sessions.resolve("sessions.json"), """
        {
          "session-key": {"sessionId":"session-value","nested":{"session_id":"session-nested"}},
          "other": {"value":"ignored"}
        }
        """);
    when(files.paths("inst_1")).thenReturn(new InstancePaths(tempDir, home, tempDir.resolve("workspace"), tempDir.resolve("logs")));

    OpenClawUserDataCleaner cleaner = new OpenClawUserDataCleaner(files, objectMapper);

    assertThat(cleaner.readOldSessionIds("inst_1", "user_11111111111111111111111111111111"))
        .containsExactlyInAnyOrder("session-value", "session-nested");
  }

  @Test
  void reportsMalformedSessionsJson() throws Exception {
    InstanceFileService files = Mockito.mock(InstanceFileService.class);
    Path home = tempDir.resolve("home");
    Path sessions = home.resolve(".openclaw/agents/user_11111111111111111111111111111111/sessions");
    Files.createDirectories(sessions);
    Files.writeString(sessions.resolve("sessions.json"), "{broken");
    when(files.paths("inst_1")).thenReturn(new InstancePaths(tempDir, home, tempDir.resolve("workspace"), tempDir.resolve("logs")));

    OpenClawUserDataCleaner cleaner = new OpenClawUserDataCleaner(files, objectMapper);

    assertThatThrownBy(() -> cleaner.readOldSessionIds("inst_1", "user_11111111111111111111111111111111"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("读取旧 Agent 会话索引失败");
  }

  @Test
  void rejectsPathTraversalAndIsIdempotentForMissingFiles() {
    InstanceFileService files = Mockito.mock(InstanceFileService.class);
    when(files.paths("inst_1")).thenReturn(new InstancePaths(tempDir, tempDir.resolve("home"), tempDir.resolve("workspace"), tempDir.resolve("logs")));
    OpenClawUserDataCleaner cleaner = new OpenClawUserDataCleaner(files, objectMapper);

    assertThatThrownBy(() -> cleaner.deleteOldUserData("inst_1", "../../outside", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    cleaner.deleteOldUserData("inst_1", "user_11111111111111111111111111111111", List.of(), List.of());
  }
}
