package com.clawbotforall.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
  void waitsForApiChannelHeartbeatAfterStartingBeforeWritingRequest() throws Exception {
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
    doAnswer(invocation -> {
      Files.createDirectories(root);
      objectMapper.writeValue(root.resolve("status.json").toFile(), Map.of(
          "running", true,
          "updatedAtEpochMs", System.currentTimeMillis(),
          "updatedAt", Instant.now().toString()
      ));
      return null;
    }).when(gatewayRpcService).startApiChannel(instance);
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
  void concurrentRequestsShareOneApiChannelStartAndWaitForHeartbeat() throws Exception {
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
    CountDownLatch startEntered = new CountDownLatch(1);
    CountDownLatch releaseStart = new CountDownLatch(1);
    doAnswer(invocation -> {
      startEntered.countDown();
      assertThat(releaseStart.await(2, TimeUnit.SECONDS)).isTrue();
      Files.createDirectories(root);
      objectMapper.writeValue(root.resolve("status.json").toFile(), Map.of(
          "running", true,
          "updatedAtEpochMs", System.currentTimeMillis(),
          "updatedAt", Instant.now().toString()
      ));
      return null;
    }).when(gatewayRpcService).startApiChannel(instance);
    ExternalApiQueueService service = new ExternalApiQueueService(fileService, objectMapper, gatewayRpcService);

    List<CompletableFuture<Map<String, Object>>> results = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      int index = i;
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("requestId", "req_concurrent_" + index);
      params.put("message", "hello " + index);
      results.add(CompletableFuture.supplyAsync(() -> service.sendApiChannelMessage(instance, params)));
    }

    assertThat(startEntered.await(2, TimeUnit.SECONDS)).isTrue();
    releaseStart.countDown();
    for (int i = 0; i < 5; i++) {
      Path requestPath = root.resolve("requests").resolve("req_concurrent_" + i + ".json");
      waitUntilExists(requestPath);
      objectMapper.writeValue(root.resolve("responses").resolve("req_concurrent_" + i + ".json").toFile(), Map.of(
          "ok", true,
          "requestId", "req_concurrent_" + i,
          "messageId", "msg_concurrent_" + i,
          "text", "OK " + i
      ));
    }

    for (int i = 0; i < 5; i++) {
      assertThat(results.get(i).get(2, TimeUnit.SECONDS).get("text")).isEqualTo("OK " + i);
    }
    verify(gatewayRpcService).startApiChannel(instance);
  }

  @Test
  void streamsJsonlDeltasBeforeFinalQueueResponse() throws Exception {
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
    writeRecentHeartbeat(root);
    ExternalApiQueueService service = new ExternalApiQueueService(fileService, objectMapper, gatewayRpcService);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("requestId", "req_stream");
    params.put("message", "hello");
    List<String> deltas = new ArrayList<>();

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(
        () -> service.streamApiChannelMessage(instance, params, deltas::add)
    );

    waitUntilExists(root.resolve("requests").resolve("req_stream.json"));
    Path streamPath = root.resolve("streams").resolve("req_stream.jsonl");
    Files.createDirectories(streamPath.getParent());
    Files.writeString(streamPath, """
        {"seq":1,"type":"delta","text":"你","createdAt":"2026-06-28T00:00:00Z"}
        {"seq":2,"type":"delta","text":"好","createdAt":"2026-06-28T00:00:01Z"}
        """);
    waitUntil(() -> deltas.size() == 2);

    objectMapper.writeValue(root.resolve("responses").resolve("req_stream.json").toFile(), Map.of(
        "ok", true,
        "requestId", "req_stream",
        "messageId", "msg_stream",
        "text", "你好"
    ));

    assertThat(result.get(2, TimeUnit.SECONDS).get("text")).isEqualTo("你好");
    assertThat(deltas).containsExactly("你", "好");
  }

  @Test
  void heartbeatRefreshesIdleDeadlineWithoutBecomingAssistantDelta() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    Path homeDir = tempDir.resolve("home");
    when(fileService.paths("inst_1")).thenReturn(new InstancePaths(
        tempDir, homeDir, tempDir.resolve("workspace"), tempDir.resolve("logs")));
    Path root = homeDir.resolve(".openclaw").resolve("claw-manager-api");
    writeRecentHeartbeat(root);
    ExternalApiQueueService service = new ExternalApiQueueService(
        fileService, objectMapper, gatewayRpcService,
        new ExternalApiQueueService.QueueTimeouts(
            Duration.ofSeconds(1), Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(25)));
    List<String> deltas = new ArrayList<>();

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(() ->
        service.streamApiChannelMessage(instance, Map.of("requestId", "req_stream_heartbeat"), deltas::add));

    waitUntilExists(root.resolve("requests").resolve("req_stream_heartbeat.json"));
    Path streamPath = root.resolve("streams").resolve("req_stream_heartbeat.jsonl");
    Files.createDirectories(streamPath.getParent());
    Files.writeString(streamPath, "{\"seq\":1,\"type\":\"delta\",\"text\":\"开始\"}\n");
    waitUntil(() -> deltas.size() == 1);

    for (int seq = 2; seq <= 9; seq++) {
      Files.writeString(streamPath,
          "{\"seq\":" + seq + ",\"type\":\"heartbeat\"}\n",
          java.nio.file.StandardOpenOption.APPEND);
      Thread.sleep(75);
    }
    objectMapper.writeValue(root.resolve("responses").resolve("req_stream_heartbeat.json").toFile(), Map.of(
        "ok", true, "requestId", "req_stream_heartbeat", "messageId", "msg_stream_heartbeat", "text", "开始"));

    assertThat(result.get(2, TimeUnit.SECONDS).get("text")).isEqualTo("开始");
    assertThat(deltas).containsExactly("开始");
  }

  @Test
  void streamsArtifactEventsBeforeFinalQueueResponse() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    Path homeDir = tempDir.resolve("home");
    when(fileService.paths("inst_1")).thenReturn(new InstancePaths(tempDir, homeDir, tempDir.resolve("workspace"), tempDir.resolve("logs")));
    Path root = homeDir.resolve(".openclaw").resolve("claw-manager-api");
    writeRecentHeartbeat(root);
    ExternalApiQueueService service = new ExternalApiQueueService(fileService, objectMapper, gatewayRpcService);
    List<Map<String, Object>> artifacts = new ArrayList<>();

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(() ->
        service.streamApiChannelMessage(instance, Map.of("requestId", "req_artifact"), text -> {}, artifacts::add));
    waitUntilExists(root.resolve("requests").resolve("req_artifact.json"));
    Path streamPath = root.resolve("streams").resolve("req_artifact.jsonl");
    Files.createDirectories(streamPath.getParent());
    Files.writeString(streamPath, "{\"seq\":1,\"type\":\"artifact\",\"artifact\":{\"id\":\"artifact-1\",\"type\":\"image_report\",\"miniappPath\":\"/pages/html-viewer/index?contentKey=x\"},\"createdAt\":\"2026-07-13T00:00:00Z\"}\n");
    waitUntil(() -> artifacts.size() == 1);
    objectMapper.writeValue(root.resolve("responses").resolve("req_artifact.json").toFile(), Map.of(
        "ok", true, "requestId", "req_artifact", "messageId", "msg", "text", "完成"));

    assertThat(result.get(2, TimeUnit.SECONDS).get("text")).isEqualTo("完成");
    assertThat(artifacts.getFirst()).containsEntry("id", "artifact-1");
  }

  @Test
  void deduplicatesStreamEventsBySequenceAndPreservesRepeatedText() throws Exception {
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
    writeRecentHeartbeat(root);
    ExternalApiQueueService service = new ExternalApiQueueService(fileService, objectMapper, gatewayRpcService);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("requestId", "req_duplicate_delta");
    params.put("message", "hello");
    List<String> deltas = new ArrayList<>();

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(
        () -> service.streamApiChannelMessage(instance, params, deltas::add)
    );

    waitUntilExists(root.resolve("requests").resolve("req_duplicate_delta.json"));
    Path streamPath = root.resolve("streams").resolve("req_duplicate_delta.jsonl");
    Files.createDirectories(streamPath.getParent());
    Files.writeString(streamPath, """
        {"seq":1,"type":"delta","text":"甲","createdAt":"2026-06-28T00:00:00Z"}
        {"seq":2,"type":"delta","text":"乙","createdAt":"2026-06-28T00:00:01Z"}
        {"seq":2,"type":"delta","text":"乙","createdAt":"2026-06-28T00:00:02Z"}
        {"seq":3,"type":"delta","text":"乙","createdAt":"2026-06-28T00:00:03Z"}
        {"seq":4,"type":"delta","text":"丙","createdAt":"2026-06-28T00:00:04Z"}
        """);
    waitUntil(() -> deltas.size() >= 4);

    objectMapper.writeValue(root.resolve("responses").resolve("req_duplicate_delta.json").toFile(), Map.of(
        "ok", true,
        "requestId", "req_duplicate_delta",
        "messageId", "msg_duplicate_delta",
        "text", "甲乙乙丙"
    ));

    assertThat(result.get(2, TimeUnit.SECONDS).get("text")).isEqualTo("甲乙乙丙");
    assertThat(deltas).containsExactly("甲", "乙", "乙", "丙");
  }

  @Test
  void waitsForCompleteJsonlLineBeforeEmittingDelta() throws Exception {
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
    writeRecentHeartbeat(root);
    ExternalApiQueueService service = new ExternalApiQueueService(fileService, objectMapper, gatewayRpcService);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("requestId", "req_partial");
    params.put("message", "hello");
    List<String> deltas = new ArrayList<>();

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(
        () -> service.streamApiChannelMessage(instance, params, deltas::add)
    );

    waitUntilExists(root.resolve("requests").resolve("req_partial.json"));
    Path streamPath = root.resolve("streams").resolve("req_partial.jsonl");
    Files.createDirectories(streamPath.getParent());
    Files.writeString(streamPath, "{\"seq\":1,\"type\":\"delta\",\"text\":\"半\"");
    Thread.sleep(300);
    assertThat(deltas).isEmpty();

    Files.writeString(streamPath, "}\n", java.nio.file.StandardOpenOption.APPEND);
    waitUntil(() -> deltas.size() == 1);

    objectMapper.writeValue(root.resolve("responses").resolve("req_partial.json").toFile(), Map.of(
        "ok", true,
        "requestId", "req_partial",
        "messageId", "msg_partial",
        "text", "半"
    ));

    assertThat(result.get(2, TimeUnit.SECONDS).get("text")).isEqualTo("半");
    assertThat(deltas).containsExactly("半");
  }

  @Test
  void streamProgressRefreshesIdleTimeoutUntilFinalResponseArrives() throws Exception {
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
    writeRecentHeartbeat(root);
    ExternalApiQueueService service = new ExternalApiQueueService(
        fileService,
        objectMapper,
        gatewayRpcService,
        new ExternalApiQueueService.QueueTimeouts(
            Duration.ofMillis(150),
            Duration.ofMillis(300),
            Duration.ofSeconds(2),
            Duration.ofMillis(10)
        )
    );

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("requestId", "req_idle_refresh");
    params.put("message", "hello");
    List<String> deltas = new ArrayList<>();

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(
        () -> service.streamApiChannelMessage(instance, params, deltas::add)
    );

    waitUntilExists(root.resolve("requests").resolve("req_idle_refresh.json"));
    Path streamPath = root.resolve("streams").resolve("req_idle_refresh.jsonl");
    Files.createDirectories(streamPath.getParent());
    Files.writeString(streamPath, """
        {"seq":1,"type":"delta","text":"甲","createdAt":"2026-06-28T00:00:00Z"}
        """);
    waitUntil(() -> deltas.size() == 1);
    Thread.sleep(70);
    Files.writeString(streamPath, """
        {"seq":2,"type":"delta","text":"乙","createdAt":"2026-06-28T00:00:01Z"}
        """, java.nio.file.StandardOpenOption.APPEND);
    waitUntil(() -> deltas.size() == 2);
    Thread.sleep(70);
    objectMapper.writeValue(root.resolve("responses").resolve("req_idle_refresh.json").toFile(), Map.of(
        "ok", true,
        "requestId", "req_idle_refresh",
        "messageId", "msg_idle_refresh",
        "text", "甲乙"
    ));

    assertThat(result.get(2, TimeUnit.SECONDS).get("text")).isEqualTo("甲乙");
    assertThat(deltas).containsExactly("甲", "乙");
  }

  @Test
  void timesOutQuicklyWhenNoInitialStreamProgressArrives() throws Exception {
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
    writeRecentHeartbeat(root);
    ExternalApiQueueService service = new ExternalApiQueueService(
        fileService,
        objectMapper,
        gatewayRpcService,
        new ExternalApiQueueService.QueueTimeouts(
            Duration.ofMillis(80),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofMillis(10)
        )
    );

    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(
        () -> service.streamApiChannelMessage(instance, Map.of("requestId", "req_first_timeout"), text -> {})
    );

    waitUntilExists(root.resolve("requests").resolve("req_first_timeout.json"));
    assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
        .isInstanceOf(java.util.concurrent.ExecutionException.class)
        .hasRootCauseMessage("API Channel 首个流式响应超时。");
  }

  @Test
  void timesOutWhenStreamIsIdleAfterProgress() throws Exception {
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
    writeRecentHeartbeat(root);
    ExternalApiQueueService service = new ExternalApiQueueService(
        fileService,
        objectMapper,
        gatewayRpcService,
        new ExternalApiQueueService.QueueTimeouts(
            Duration.ofMillis(150),
            Duration.ofMillis(80),
            Duration.ofSeconds(2),
            Duration.ofMillis(10)
        )
    );

    List<String> deltas = new ArrayList<>();
    CompletableFuture<Map<String, Object>> result = CompletableFuture.supplyAsync(
        () -> service.streamApiChannelMessage(instance, Map.of("requestId", "req_idle_timeout"), deltas::add)
    );

    waitUntilExists(root.resolve("requests").resolve("req_idle_timeout.json"));
    Path streamPath = root.resolve("streams").resolve("req_idle_timeout.jsonl");
    Files.createDirectories(streamPath.getParent());
    Files.writeString(streamPath, """
        {"seq":1,"type":"delta","text":"甲","createdAt":"2026-06-28T00:00:00Z"}
        """);
    waitUntil(() -> deltas.size() == 1);

    assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
        .isInstanceOf(java.util.concurrent.ExecutionException.class)
        .hasRootCauseMessage("API Channel 流式响应空闲超时。");
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
        .hasMessageContaining("API Channel 未注册");
  }

  private void writeRecentHeartbeat(Path root) throws Exception {
    Files.createDirectories(root);
    objectMapper.writeValue(root.resolve("status.json").toFile(), Map.of(
        "running", true,
        "updatedAtEpochMs", System.currentTimeMillis(),
        "updatedAt", Instant.now().toString()
    ));
  }

  private static void waitUntilExists(Path path) throws Exception {
    waitUntil(() -> Files.exists(path));
  }

  private static void waitUntil(CheckedBooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for condition");
  }

  @FunctionalInterface
  private interface CheckedBooleanSupplier {
    boolean getAsBoolean() throws Exception;
  }
}
