package com.clawbotforall.externalapi;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.wechat.OpenClawGatewayRpcService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExternalApiQueueService {
  private static final Logger log = LoggerFactory.getLogger(ExternalApiQueueService.class);
  private static final QueueTimeouts DEFAULT_QUEUE_TIMEOUTS = new QueueTimeouts(
      Duration.ofSeconds(300),
      Duration.ofSeconds(120),
      Duration.ofSeconds(900),
      Duration.ofMillis(200)
  );
  private static final Duration MONITOR_HEARTBEAT_TTL = Duration.ofSeconds(30);
  private static final Duration MONITOR_START_HEARTBEAT_TIMEOUT = Duration.ofSeconds(10);
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final InstanceFileService fileService;
  private final ObjectMapper objectMapper;
  private final OpenClawGatewayRpcService gatewayRpcService;
  private final ConcurrentMap<String, Object> startLocks = new ConcurrentHashMap<>();
  private final QueueTimeouts queueTimeouts;

  @Autowired
  public ExternalApiQueueService(
      InstanceFileService fileService,
      ObjectMapper objectMapper,
      OpenClawGatewayRpcService gatewayRpcService
  ) {
    this(fileService, objectMapper, gatewayRpcService, DEFAULT_QUEUE_TIMEOUTS);
  }

  ExternalApiQueueService(
      InstanceFileService fileService,
      ObjectMapper objectMapper,
      OpenClawGatewayRpcService gatewayRpcService,
      QueueTimeouts queueTimeouts
  ) {
    this.fileService = fileService;
    this.objectMapper = objectMapper;
    this.gatewayRpcService = gatewayRpcService;
    this.queueTimeouts = normalizeTimeouts(queueTimeouts);
  }

  public Map<String, Object> sendApiChannelMessage(InstanceEntity instance, Map<String, Object> params) {
    return streamApiChannelMessage(instance, params, text -> {});
  }

  public Map<String, Object> streamApiChannelMessage(
      InstanceEntity instance,
      Map<String, Object> params,
      StreamDeltaConsumer onDelta
  ) {
    String requestId = stringValue(params == null ? null : params.get("requestId"));
    if (requestId.isBlank()) {
      throw new IllegalArgumentException("requestId 不能为空。");
    }
    Path root = queueRoot(instance);
    ensureApiChannelStartedOrRecentlyAlive(instance, root);
    Path requests = root.resolve("requests");
    Path responses = root.resolve("responses");
    Path streams = root.resolve("streams");
    try {
      Files.createDirectories(requests);
      Files.createDirectories(responses);
      Files.createDirectories(streams);
      Path responsePath = responses.resolve(requestId + ".json");
      Path streamPath = streams.resolve(requestId + ".jsonl");
      Files.deleteIfExists(responsePath);
      Files.deleteIfExists(streamPath);
      writeRequest(requests.resolve(requestId + ".json"), params == null ? Map.of() : params);
      return waitForResponse(responsePath, streamPath, queueTimeouts, onDelta == null ? text -> {} : onDelta);
    } catch (IOException error) {
      throw new IllegalStateException("API Channel 队列读写失败：" + message(error), error);
    }
  }

  private void ensureApiChannelStartedOrRecentlyAlive(InstanceEntity instance, Path root) {
    if (hasRecentMonitorHeartbeat(root)) {
      return;
    }
    String instanceId = instance.getId() == null || instance.getId().isBlank() ? "__unknown__" : instance.getId();
    Object lock = startLocks.computeIfAbsent(instanceId, ignored -> new Object());
    synchronized (lock) {
      if (hasRecentMonitorHeartbeat(root)) {
        return;
      }
      startApiChannelAndWaitForHeartbeat(instance, root);
    }
  }

  private void startApiChannelAndWaitForHeartbeat(InstanceEntity instance, Path root) {
    try {
      gatewayRpcService.startApiChannel(instance);
    } catch (RuntimeException error) {
      if (!hasRecentMonitorHeartbeat(root)) {
        throw normalizeApiChannelStartError(error);
      }
      log.warn(
          "OpenClaw API Channel start failed but recent queue monitor heartbeat exists, continuing with queue: instanceId={}, reason={}",
          instance.getId(),
          message(error)
      );
    }
    if (!waitForRecentMonitorHeartbeat(root, MONITOR_START_HEARTBEAT_TIMEOUT)) {
      throw new IllegalStateException("OpenClaw API Channel 已请求启动，但未检测到队列 monitor heartbeat，请确认插件已安装并重启 Gateway。");
    }
  }

  private RuntimeException normalizeApiChannelStartError(RuntimeException error) {
    String message = message(error);
    if (message.toLowerCase().contains("invalid channels.start channel")) {
      return new IllegalStateException("OpenClaw API Channel 未注册或未加载，请确认已安装 API Channel 插件并重启 Gateway。", error);
    }
    return error;
  }

  private boolean waitForRecentMonitorHeartbeat(Path root, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (hasRecentMonitorHeartbeat(root)) {
        return true;
      }
      try {
        Thread.sleep(DEFAULT_QUEUE_TIMEOUTS.pollInterval().toMillis());
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return hasRecentMonitorHeartbeat(root);
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

  private Map<String, Object> waitForResponse(
      Path responsePath,
      Path streamPath,
      QueueTimeouts timeouts,
      StreamDeltaConsumer onDelta
  ) throws IOException {
    long startedAt = System.nanoTime();
    long absoluteDeadline = startedAt + timeouts.absoluteTimeout().toNanos();
    long firstProgressDeadline = startedAt + timeouts.firstDeltaTimeout().toNanos();
    long idleDeadline = startedAt + timeouts.idleTimeout().toNanos();
    boolean sawProgress = false;
    StreamReadState streamState = new StreamReadState();
    while (System.nanoTime() < absoluteDeadline) {
      StreamReadResult streamRead = readStreamEvents(streamPath, streamState, onDelta);
      if (streamRead.progressed()) {
        sawProgress = true;
        idleDeadline = System.nanoTime() + timeouts.idleTimeout().toNanos();
      }
      if (Files.exists(responsePath)) {
        streamRead = readStreamEvents(streamPath, streamState, onDelta);
        if (streamRead.progressed()) {
          sawProgress = true;
          idleDeadline = System.nanoTime() + timeouts.idleTimeout().toNanos();
        }
        Map<String, Object> response = objectMapper.readValue(responsePath.toFile(), MAP_TYPE);
        Files.deleteIfExists(responsePath);
        Files.deleteIfExists(streamPath);
        if (Boolean.TRUE.equals(response.get("ok"))) {
          return response;
        }
        throw new IllegalStateException("API Channel 处理失败：" + stringValue(response.get("error")));
      }
      long now = System.nanoTime();
      if (!sawProgress && now >= firstProgressDeadline) {
        throw new IllegalStateException("API Channel 首个流式响应超时。");
      }
      if (sawProgress && now >= idleDeadline) {
        throw new IllegalStateException("API Channel 流式响应空闲超时。");
      }
      sleep(timeouts);
    }
    throw new IllegalStateException("API Channel 等待响应超过最大总时长。");
  }

  private StreamReadResult readStreamEvents(Path streamPath, StreamReadState state, StreamDeltaConsumer onDelta) throws IOException {
    if (!Files.exists(streamPath)) {
      return new StreamReadResult(false);
    }
    long size = Files.size(streamPath);
    if (size < state.offset) {
      state.offset = 0;
      state.pending.setLength(0);
    }
    if (size <= state.offset) {
      return new StreamReadResult(false);
    }
    byte[] bytes;
    try (var input = Files.newInputStream(streamPath)) {
      input.skipNBytes(state.offset);
      bytes = input.readAllBytes();
    }
    state.offset = size;
    state.pending.append(new String(bytes, StandardCharsets.UTF_8));
    boolean progressed = bytes.length > 0;
    int newlineIndex;
    while ((newlineIndex = indexOfNewline(state.pending)) >= 0) {
      String line = state.pending.substring(0, newlineIndex).trim();
      int removeLength = newlineIndex + 1;
      if (removeLength < state.pending.length() && state.pending.charAt(newlineIndex) == '\r' && state.pending.charAt(removeLength) == '\n') {
        removeLength += 1;
      }
      state.pending.delete(0, removeLength);
      if (line.isBlank()) {
        continue;
      }
      Map<String, Object> event = objectMapper.readValue(line, MAP_TYPE);
      String type = stringValue(event.get("type"));
      if ("delta".equals(type)) {
        String text = valueString(event.get("text"));
        if (!text.isBlank()) {
          if (isDuplicateSingleCharacterTailDelta(state.emitted, text)) {
            continue;
          }
          onDelta.accept(text);
          state.emitted.append(text);
        }
      } else if ("error".equals(type)) {
        throw new IllegalStateException("API Channel 处理失败：" + valueString(event.get("error")));
      }
    }
    return new StreamReadResult(progressed);
  }

  private static boolean isDuplicateSingleCharacterTailDelta(StringBuilder emitted, String text) {
    if (emitted.isEmpty() || text.isEmpty() || text.codePointCount(0, text.length()) != 1) {
      return false;
    }
    return emitted.toString().endsWith(text);
  }

  private static int indexOfNewline(StringBuilder builder) {
    for (int i = 0; i < builder.length(); i++) {
      char ch = builder.charAt(i);
      if (ch == '\n' || ch == '\r') {
        return i;
      }
    }
    return -1;
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

  private static void sleep(QueueTimeouts timeouts) {
    try {
      Thread.sleep(timeouts.pollInterval().toMillis());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("API Channel 等待响应被中断。", error);
    }
  }

  private static QueueTimeouts normalizeTimeouts(QueueTimeouts raw) {
    QueueTimeouts value = raw == null ? DEFAULT_QUEUE_TIMEOUTS : raw;
    return new QueueTimeouts(
        positiveDuration(value.firstDeltaTimeout(), DEFAULT_QUEUE_TIMEOUTS.firstDeltaTimeout()),
        positiveDuration(value.idleTimeout(), DEFAULT_QUEUE_TIMEOUTS.idleTimeout()),
        positiveDuration(value.absoluteTimeout(), DEFAULT_QUEUE_TIMEOUTS.absoluteTimeout()),
        positiveDuration(value.pollInterval(), DEFAULT_QUEUE_TIMEOUTS.pollInterval())
    );
  }

  private static Duration positiveDuration(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String valueString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? String.valueOf(error) : message;
  }

  @FunctionalInterface
  public interface StreamDeltaConsumer {
    void accept(String text) throws IOException;
  }

  private static class StreamReadState {
    private long offset;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder emitted = new StringBuilder();
  }

  record QueueTimeouts(
      Duration firstDeltaTimeout,
      Duration idleTimeout,
      Duration absoluteTimeout,
      Duration pollInterval
  ) {}

  private record StreamReadResult(boolean progressed) {}
}
