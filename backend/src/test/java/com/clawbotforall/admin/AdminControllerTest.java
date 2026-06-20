package com.clawbotforall.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.ws.AppEventPublisher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AdminControllerTest {

  @TempDir
  Path tempDir;

  @Test
  void serverLogsReadOnlyRequestedTailFromConfiguredLogFile() throws Exception {
    Path logDir = tempDir.resolve("logs");
    Files.createDirectories(logDir);
    Path logPath = logDir.resolve("server.log");
    Files.write(
        logPath,
        IntStream.rangeClosed(1, 3_000)
            .mapToObj(index -> "line-" + index)
            .toList(),
        StandardCharsets.UTF_8
    );
    AdminController controller = new AdminController(
        mock(OpenClawRuntime.class),
        new ClawbotProperties(
            new ClawbotProperties.Paths(tempDir.toString()),
            null,
            null,
            null
        ),
        mock(AppEventPublisher.class)
    );

    Map<String, Object> response = controller.serverLogs(100, authentication());

    @SuppressWarnings("unchecked")
    Map<String, Object> logs = (Map<String, Object>) response.get("logs");
    @SuppressWarnings("unchecked")
    List<String> lines = (List<String>) logs.get("lines");
    assertThat(logs.get("path")).isEqualTo(logPath.toString());
    assertThat(logs.get("tail")).isEqualTo(100);
    assertThat(lines).hasSize(100);
    assertThat(lines.getFirst()).isEqualTo("line-2901");
    assertThat(lines.getLast()).isEqualTo("line-3000");
    assertThat(logs.get("text").toString()).startsWith("line-2901\n");
  }

  private static TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new AuthenticatedAdmin(
            "admin_1",
            "admin@example.test",
            "Admin",
            false,
            "2026-06-20T00:00:00Z",
            "2026-06-20T00:00:00Z"
        ),
        null
    );
  }
}
