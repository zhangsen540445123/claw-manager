package com.clawbotforall.externalapi;

import com.clawbotforall.web.ApiException;
import com.clawbotforall.web.ExternalRequestIds;
import com.clawbotforall.miniapp.MiniappChatRoute;
import com.clawbotforall.miniapp.MiniappUserAccessService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  private static final long SSE_TIMEOUT_MS = 960_000L;

  private final MiniappUserAccessService userAccessService;
  private final ExternalApiQueueService queueService;
  private final Executor executor = Executors.newCachedThreadPool(task -> {
    Thread thread = new Thread(task, "external-api-chat-" + System.nanoTime());
    thread.setDaemon(true);
    return thread;
  });

  public ExternalApiChatController(
      MiniappUserAccessService userAccessService,
      ExternalApiQueueService queueService
  ) {
    this.userAccessService = userAccessService;
    this.queueService = queueService;
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
          "external.chat.accepted cmRequestId={} chatRequestId={} openidPreview={} senderHash={} instanceId={} openVikingUserId={} conversationId={} metadataSource={} messageLength={} elapsedMs={}",
          cmRequestId,
          requestId,
          preview(route.openid()),
          safe(route.openidHash()),
          route.instance().getId(),
          safe(route.openvikingUserId()),
          safe(conversationId),
          metadataSource(payload.metadata()),
          message.length(),
          elapsedMs(acceptedAt)
      );
    } catch (RuntimeException error) {
      log.warn(
          "external.chat.rejected cmRequestId={} openidPreview={} metadataSource={} elapsedMs={} error={}",
          cmRequestId,
          preview(payload.openid()),
          metadataSource(payload.metadata()),
          elapsedMs(acceptedAt),
          errorMessage(error)
      );
      throw error;
    }

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    executor.execute(() -> {
      long startedAt = System.nanoTime();
      int[] deltaCount = {0};
      long[] firstDeltaMs = {-1};
      try {
        send(emitter, "start", Map.of(
            "requestId", requestId,
            "instanceId", route.instance().getId(),
            "conversationId", conversationId,
            "openVikingUserId", route.openvikingUserId()
        ));
        log.info(
            "external.chat.streamStart cmRequestId={} chatRequestId={} instanceId={} openVikingUserId={} conversationId={}",
            cmRequestId,
            requestId,
            route.instance().getId(),
            safe(route.openvikingUserId()),
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
            send(emitter, "delta", Map.of("text", text));
            sentDelta[0] = true;
          }
        }, artifact -> {
          send(emitter, "artifact", artifact);
        });
        String text = stringify(result.get("text"));
        if (!sentDelta[0] && !text.isBlank()) {
          send(emitter, "delta", Map.of("text", text));
          firstDeltaMs[0] = elapsedMs(startedAt);
          deltaCount[0] += 1;
        }
        send(emitter, "done", Map.of(
            "requestId", requestId,
            "messageId", stringify(result.get("messageId")),
            "openVikingUserId", route.openvikingUserId(),
            "finishedAt", Instant.now().toString()
        ));
        log.info(
            "external.chat.done cmRequestId={} chatRequestId={} instanceId={} openVikingUserId={} conversationId={} deltaCount={} firstDeltaMs={} elapsedMs={} messageId={}",
            cmRequestId,
            requestId,
            route.instance().getId(),
            safe(route.openvikingUserId()),
            safe(conversationId),
            deltaCount[0],
            firstDeltaMs[0],
            elapsedMs(startedAt),
            safe(stringify(result.get("messageId")))
        );
        emitter.complete();
      } catch (RuntimeException | IOException error) {
        log.warn(
            "external.chat.error cmRequestId={} chatRequestId={} instanceId={} openVikingUserId={} conversationId={} deltaCount={} firstDeltaMs={} elapsedMs={} error={}",
            cmRequestId,
            requestId,
            route.instance().getId(),
            safe(route.openvikingUserId()),
            safe(conversationId),
            deltaCount[0],
            firstDeltaMs[0],
            elapsedMs(startedAt),
            errorMessage(error)
        );
        try {
          send(emitter, "error", Map.of(
              "requestId", requestId,
              "code", "OPENCLAW_API_CHANNEL_ERROR",
              "message", error.getMessage() == null ? String.valueOf(error) : error.getMessage()
          ));
        } catch (IOException ignored) {
          // client is gone
        }
        emitter.complete();
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
    params.put("openVikingUserId", route.openvikingUserId());
    params.put("senderHash", route.openidHash());
    params.put("senderId", route.senderId());
    params.put("conversationId", conversationId);
    params.put("conversationHash", conversationHash);
    params.put("message", message);
    params.put("metadata", metadata == null ? Map.of() : metadata);
    return params;
  }

  private void send(SseEmitter emitter, String event, Map<String, Object> data) throws IOException {
    emitter.send(SseEmitter.event().name(event).data(data));
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

  private static String preview(String value) {
    String normalized = trim(value);
    if (normalized.length() <= 10) {
      return normalized.isBlank() ? "-" : normalized;
    }
    return normalized.substring(0, 6) + "..." + normalized.substring(normalized.length() - 4);
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
