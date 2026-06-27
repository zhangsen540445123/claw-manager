package com.clawbotforall.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.wechat.OpenClawGatewayRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalApiQueueServiceTest {

  @TempDir
  Path tempDir;

  @Mock
  InstanceFileService fileService;

  @Mock
  OpenClawGatewayRpcService gatewayRpcService;

  ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void startsApiChannelBeforeWaitingForQueueResponse() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    Path homeDir = tempDir.resolve("home");
    when(fileService.paths("inst_1")).thenReturn(new InstancePaths(
        tempDir,
        homeDir,
        tempDir.resolve("workspace"),
        tempDir.resolve("logs")
    ));
    ExternalApiQueueService service = new ExternalApiQueueService(fileService, objectMapper, gatewayRpcService);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("requestId", "req_1");
    params.put("message", "hello");

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(
        () -> service.sendApiChannelMessage(instance, params)
    );

    Path requestPath = homeDir.resolve(".openclaw").resolve("claw-manager-api").resolve("requests").resolve("req_1.json");
    waitUntilExists(requestPath);
    Path responsePath = homeDir.resolve(".openclaw").resolve("claw-manager-api").resolve("responses").resolve("req_1.json");
    objectMapper.writeValue(responsePath.toFile(), Map.of(
        "ok", true,
        "requestId", "req_1",
        "messageId", "msg_1",
        "text", "OK"
    ));

    assertThat(result.get(2, TimeUnit.SECONDS).get("text")).isEqualTo("OK");
    verify(gatewayRpcService).startApiChannel(instance);
  }

  @Test
  void skipsGatewayStartWhenMonitorHeartbeatIsRecent() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    Path homeDir = tempDir.resolve("home");
    when(fileService.paths("inst_1")).thenReturn(new InstancePaths(
        tempDir,
        homeDir,
        tempDir.resolve("workspace"),
        tempDir.resolve("logs")
    ));
    Path root = homeDir.resolve(".openclaw").resolve("claw-manager-api");
    Files.createDirectories(root);
    objectMapper.writeValue(root.resolve("status.json").toFile(), Map.of(
        "running", true,
        "updatedAtEpochMs", System.currentTimeMillis(),
        "updatedAt", Instant.now().toString()
    ));
    ExternalApiQueueService service = new ExternalApiQueueService(fileService, objectMapper, gatewayRpcService);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("requestId", "req_heartbeat");
    params.put("message", "hello");

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(
        () -> service.sendApiChannelMessage(instance, params)
    );

    Path requestPath = root.resolve("requests").resolve("req_heartbeat.json");
    waitUntilExists(requestPath);
    objectMapper.writeValue(root.resolve("responses").resolve("req_heartbeat.json").toFile(), Map.of(
        "ok", true,
        "requestId", "req_heartbeat",
        "messageId", "msg_heartbeat",
        "text", "OK heartbeat"
    ));

    assertThat(result.get(2, TimeUnit.SECONDS).get("text")).isEqualTo("OK heartbeat");
    verify(gatewayRpcService, never()).startApiChannel(instance);
  }

  @Test
  void rethrowsGatewayStartFailureWhenMonitorHeartbeatIsMissing() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    Path homeDir = tempDir.resolve("home");
    when(fileService.paths("inst_1")).thenReturn(new InstancePaths(
        tempDir,
        homeDir,
        tempDir.resolve("workspace"),
        tempDir.resolve("logs")
    ));
    doThrow(new IllegalStateException("invalid channels.start channel"))
        .when(gatewayRpcService)
        .startApiChannel(instance);
    ExternalApiQueueService service = new ExternalApiQueueService(fileService, objectMapper, gatewayRpcService);

    assertThatThrownBy(() -> service.sendApiChannelMessage(instance, Map.of("requestId", "req_missing")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid channels.start channel");
  }

  private static void waitUntilExists(Path path) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (Files.exists(path)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + path);
  }
}
