package com.clawbotforall.externalapi;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.wechat.OpenClawGatewayRpcService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExternalApiQueueService {
  private static final Logger log = LoggerFactory.getLogger(ExternalApiQueueService.class);
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(180);
  private static final Duration MONITOR_HEARTBEAT_TTL = Duration.ofSeconds(30);
  private static final long POLL_INTERVAL_MS = 200L;
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final InstanceFileService fileService;
  private final ObjectMapper objectMapper;
  private final OpenClawGatewayRpcService gatewayRpcService;

  public ExternalApiQueueService(
      InstanceFileService fileService,
      ObjectMapper objectMapper,
      OpenClawGatewayRpcService gatewayRpcService
  ) {
    this.fileService = fileService;
    this.objectMapper = objectMapper;
    this.gatewayRpcService = gatewayRpcService;
  }

  public Map<String, Object> sendApiChannelMessage(InstanceEntity instance, Map<String, Object> params) {
    String requestId = stringValue(params == null ? null : params.get("requestId"));
    if (requestId.isBlank()) {
      throw new IllegalArgumentException("requestId 不能为空。");
    }
    Path root = queueRoot(instance);
    ensureApiChannelStartedOrRecentlyAlive(instance, root);
    Path requests = root.resolve("requests");
    Path responses = root.resolve("responses");
    try {
      Files.createDirectories(requests);
      Files.createDirectories(responses);
      Path responsePath = responses.resolve(requestId + ".json");
      Files.deleteIfExists(responsePath);
      writeRequest(requests.resolve(requestId + ".json"), params == null ? Map.of() : params);
      return waitForResponse(responsePath, RESPONSE_TIMEOUT);
    } catch (IOException error) {
      throw new IllegalStateException("API Channel 队列读写失败：" + message(error), error);
    }
  }

  private void ensureApiChannelStartedOrRecentlyAlive(InstanceEntity instance, Path root) {
    if (hasRecentMonitorHeartbeat(root)) {
      return;
    }
    try {
      gatewayRpcService.startApiChannel(instance);
    } catch (RuntimeException error) {
      if (!hasRecentMonitorHeartbeat(root)) {
        throw error;
      }
      log.warn(
          "OpenClaw API Channel start failed but recent queue monitor heartbeat exists, continuing with queue: instanceId={}, reason={}",
          instance.getId(),
          message(error)
      );
    }
  }

  private boolean hasRecentMonitorHeartbeat(Path root) {
    Path statusPath = root.resolve("status.json");
    if (!Files.isRegularFile(statusPath)) {
      return false;
    }
    try {
      Map<String, Object> status = objectMapper.readValue(statusPath.toFile(), MAP_TYPE);
      if (!Boolean.TRUE.equals(status.get("running"))) {
        return false;
      }
      long updatedAtEpochMs = epochMillis(status);
      return updatedAtEpochMs > 0
          && System.currentTimeMillis() - updatedAtEpochMs <= MONITOR_HEARTBEAT_TTL.toMillis();
    } catch (Exception ignored) {
      return false;
    }
  }

  private static long epochMillis(Map<String, Object> status) {
    Object updatedAtEpochMs = status.get("updatedAtEpochMs");
    if (updatedAtEpochMs instanceof Number number) {
      return number.longValue();
    }
    if (updatedAtEpochMs instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException ignored) {
        // Fall through to updatedAt parsing.
      }
    }
    Object updatedAt = status.get("updatedAt");
    if (updatedAt instanceof String text && !text.isBlank()) {
      try {
        return Instant.parse(text.trim()).toEpochMilli();
      } catch (Exception ignored) {
        return 0;
      }
    }
    return 0;
  }

  private void writeRequest(Path requestPath, Map<String, Object> params) throws IOException {
    Map<String, Object> request = new LinkedHashMap<>(params);
    request.putIfAbsent("createdAt", Instant.now().toString());
    Path tmp = requestPath.resolveSibling(requestPath.getFileName() + ".tmp-" + System.nanoTime());
    objectMapper.writeValue(tmp.toFile(), request);
    moveReplace(tmp, requestPath);
  }

  private Map<String, Object> waitForResponse(Path responsePath, Duration timeout) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.exists(responsePath)) {
        Map<String, Object> response = objectMapper.readValue(responsePath.toFile(), MAP_TYPE);
        Files.deleteIfExists(responsePath);
        if (Boolean.TRUE.equals(response.get("ok"))) {
          return response;
        }
        throw new IllegalStateException("API Channel 处理失败：" + stringValue(response.get("error")));
      }
      sleep();
    }
    throw new IllegalStateException("API Channel 等待响应超时。");
  }

  private Path queueRoot(InstanceEntity instance) {
    return fileService.paths(instance.getId()).homeDir().resolve(".openclaw").resolve("claw-manager-api");
  }

  private static void moveReplace(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL_MS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("API Channel 等待响应被中断。", error);
    }
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? String.valueOf(error) : message;
  }
}
