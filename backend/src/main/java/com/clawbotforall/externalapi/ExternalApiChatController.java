package com.clawbotforall.externalapi;

import com.clawbotforall.web.ApiException;
import com.clawbotforall.web.ExternalRequestIds;
import com.clawbotforall.miniapp.MiniappChatRoute;
import com.clawbotforall.miniapp.MiniappUserAccessService;
import com.clawbotforall.wechat.WechatLogSanitizer;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ExternalApiChatController {
  private static final Logger log = LoggerFactory.getLogger(ExternalApiChatController.class);
  private static final Duration SSE_TIMEOUT = Duration.ofMinutes(16);
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

  private final MiniappUserAccessService userAccessService;
  private final ExternalApiQueueService queueService;
  private final Executor executor;
  private final ScheduledExecutorService heartbeatExecutor;
  private final Duration heartbeatInterval;
  private final Duration sseTimeout;

  @Autowired
  public ExternalApiChatController(
      MiniappUserAccessService userAccessService,
      ExternalApiQueueService queueService
  ) {
    this(
        userAccessService,
        queueService,
        Executors.newCachedThreadPool(task -> daemonThread(task, "external-api-chat-")),
        Executors.newSingleThreadScheduledExecutor(task -> daemonThread(task, "external-api-heartbeat-")),
        HEARTBEAT_INTERVAL,
        SSE_TIMEOUT
    );
  }

  ExternalApiChatController(
      MiniappUserAccessService userAccessService,
      ExternalApiQueueService queueService,
      Executor executor,
      ScheduledExecutorService heartbeatExecutor,
      Duration heartbeatInterval,
      Duration sseTimeout
  ) {
    this.userAccessService = userAccessService;
    this.queueService = queueService;
    this.executor = executor;
    this.heartbeatExecutor = heartbeatExecutor;
    this.heartbeatInterval = heartbeatInterval;
    this.sseTimeout = sseTimeout;
  }

  @PreDestroy
  void shutdownExecutors() {
    if (executor instanceof ExecutorService service) service.shutdownNow();
    heartbeatExecutor.shutdownNow();
  }

  @PostMapping("/api/external/openclaw/chat/stream")
  public SseEmitter streamChat(
      @RequestBody(required = false) ExternalApiChatRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      HttpServletResponse response
  ) {
    long acceptedAt = System.nanoTime();
    String cmRequestId = ExternalRequestIds.create();
    response.setHeader(ExternalRequestIds.HEADER, cmRequestId);
    ExternalApiChatRequest payload = request == null ? new ExternalApiChatRequest("", "", "", Map.of()) : request;
    String message;
    String requestId;
    MiniappChatRoute route;
    String conversationId;
    String conversationHash;
    try {
      message = trim(payload.message());
      if (message.isBlank()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空。");
      }
      requestId = UUID.randomUUID().toString();
      route = userAccessService.resolveChatRoute(authorization, payload.openid());
      conversationId = trim(payload.conversationId()).isBlank() ? "default" : trim(payload.conversationId());
      conversationHash = userAccessService.conversationHash(conversationId);
      log.info(
          "external.chat.accepted cmRequestId={} chatRequestId={} openidHash={} senderHash={} instanceId={} openVikingUserHash={} conversationId={} metadataSource={} messageLength={} elapsedMs={}",
          cmRequestId,
          requestId,
          WechatLogSanitizer.identityHashPreview(route.openid()),
          safe(route.openidHash()),
          route.instance().getId(),
          WechatLogSanitizer.identityHashPreview(route.openvikingUserId()),
          safe(conversationId),
          metadataSource(payload.metadata()),
          message.length(),
          elapsedMs(acceptedAt)
      );
    } catch (RuntimeException error) {
      log.warn(
          "external.chat.rejected cmRequestId={} openidHash={} metadataSource={} elapsedMs={} error={}",
          cmRequestId,
          WechatLogSanitizer.identityHashPreview(payload.openid()),
          metadataSource(payload.metadata()),
          elapsedMs(acceptedAt),
          errorMessage(error)
      );
      throw error;
    }

    SseEmitter emitter = new SseEmitter(sseTimeout.toMillis());
    StreamSession stream = new StreamSession(emitter, heartbeatExecutor, heartbeatInterval, requestId);
    emitter.onCompletion(stream::cancel);
    emitter.onTimeout(stream::cancel);
    emitter.onError(ignored -> stream.cancel());
    executor.execute(() -> {
      long startedAt = System.nanoTime();
      int[] deltaCount = {0};
      long[] firstDeltaMs = {-1};
      try {
        stream.send("start", Map.of(
            "requestId", requestId,
            "instanceId", route.instance().getId(),
            "conversationId", conversationId,
            "openVikingUserId", route.openvikingUserId()
        ));
        stream.startHeartbeat();
        log.info(
            "external.chat.streamStart cmRequestId={} chatRequestId={} instanceId={} openVikingUserHash={} conversationId={}",
            cmRequestId,
            requestId,
            route.instance().getId(),
            WechatLogSanitizer.identityHashPreview(route.openvikingUserId()),
            safe(conversationId)
        );
        boolean[] sentDelta = {false};
        Map<String, Object> result = queueService.streamApiChannelMessage(route.instance(), gatewayParams(
            requestId,
            route,
            conversationId,
            conversationHash,
            message,
            payload.metadata()
        ), text -> {
          if (!text.isBlank()) {
            if (deltaCount[0] == 0) {
              firstDeltaMs[0] = elapsedMs(startedAt);
            }
            deltaCount[0] += 1;
            stream.send("delta", Map.of("text", text));
            sentDelta[0] = true;
          }
        }, artifact -> {
          stream.send("artifact", artifact);
        });
        String text = stringify(result.get("text"));
        if (!sentDelta[0] && !text.isBlank()) {
          stream.send("delta", Map.of("text", text));
          firstDeltaMs[0] = elapsedMs(startedAt);
          deltaCount[0] += 1;
        }
        stream.complete("done", Map.of(
            "requestId", requestId,
            "messageId", stringify(result.get("messageId")),
            "openVikingUserId", route.openvikingUserId(),
            "finishedAt", Instant.now().toString()
        ));
        log.info(
            "external.chat.done cmRequestId={} chatRequestId={} instanceId={} openVikingUserHash={} conversationId={} deltaCount={} firstDeltaMs={} elapsedMs={} messageId={}",
            cmRequestId,
            requestId,
            route.instance().getId(),
            WechatLogSanitizer.identityHashPreview(route.openvikingUserId()),
            safe(conversationId),
            deltaCount[0],
            firstDeltaMs[0],
            elapsedMs(startedAt),
            safe(stringify(result.get("messageId")))
        );
      } catch (RuntimeException | IOException error) {
        log.warn(
            "external.chat.error cmRequestId={} chatRequestId={} instanceId={} openVikingUserHash={} conversationId={} deltaCount={} firstDeltaMs={} elapsedMs={} error={}",
            cmRequestId,
            requestId,
            route.instance().getId(),
            WechatLogSanitizer.identityHashPreview(route.openvikingUserId()),
            safe(conversationId),
            deltaCount[0],
            firstDeltaMs[0],
            elapsedMs(startedAt),
            errorMessage(error)
        );
        try {
          stream.complete("error", Map.of(
              "requestId", requestId,
              "code", "OPENCLAW_API_CHANNEL_ERROR",
              "message", error.getMessage() == null ? String.valueOf(error) : error.getMessage()
          ));
        } catch (IOException ignored) {
          // client is gone
        }
        stream.cancel();
      }
    });
    return emitter;
  }

  private Map<String, Object> gatewayParams(
      String requestId,
      MiniappChatRoute route,
      String conversationId,
      String conversationHash,
      String message,
      Map<String, Object> metadata
  ) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("requestId", requestId);
    params.put("agentId", route.agentId());
    params.put("openVikingUserId", route.openvikingUserId());
    params.put("senderHash", route.openidHash());
    params.put("senderId", route.senderId());
    params.put("conversationId", conversationId);
    params.put("conversationHash", conversationHash);
    params.put("message", message);
    params.put("metadata", metadata == null ? Map.of() : metadata);
    return params;
  }

  private static Thread daemonThread(Runnable task, String prefix) {
    Thread thread = new Thread(task, prefix + System.nanoTime());
    thread.setDaemon(true);
    return thread;
  }

  private static final class StreamSession {
    private final SseEmitter emitter;
    private final ScheduledExecutorService scheduler;
    private final Duration interval;
    private final String requestId;
    private ScheduledFuture<?> heartbeatTask;
    private boolean closed;

    private StreamSession(SseEmitter emitter, ScheduledExecutorService scheduler, Duration interval, String requestId) {
      this.emitter = emitter;
      this.scheduler = scheduler;
      this.interval = interval;
      this.requestId = requestId;
    }

    synchronized void startHeartbeat() {
      if (closed || heartbeatTask != null) return;
      heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
        try {
          send("heartbeat", Map.of("requestId", requestId));
        } catch (IOException error) {
          cancel();
        }
      }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    synchronized void send(String event, Map<String, Object> data) throws IOException {
      if (closed) return;
      emitter.send(SseEmitter.event().name(event).data(data));
    }

    synchronized void complete(String event, Map<String, Object> data) throws IOException {
      if (closed) return;
      stopHeartbeat();
      emitter.send(SseEmitter.event().name(event).data(data));
      closed = true;
      emitter.complete();
    }

    synchronized void cancel() {
      if (closed) return;
      closed = true;
      stopHeartbeat();
    }

    private void stopHeartbeat() {
      if (heartbeatTask != null) {
        heartbeatTask.cancel(false);
        heartbeatTask = null;
      }
    }
  }

  private static String stringify(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static long elapsedMs(long startedAt) {
    return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private static String metadataSource(Map<String, Object> metadata) {
    if (metadata == null) {
      return "-";
    }
    return safe(stringify(metadata.get("source")));
  }

  private static String safe(String value) {
    String normalized = trim(value);
    return normalized.isBlank() ? "-" : normalized;
  }

  private static String errorMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  public record ExternalApiChatRequest(
      String openid,
      String conversationId,
      String message,
      Map<String, Object> metadata
  ) {}
}
