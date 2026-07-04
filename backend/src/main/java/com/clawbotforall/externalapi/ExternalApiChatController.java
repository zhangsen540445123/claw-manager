package com.clawbotforall.externalapi;

import com.clawbotforall.web.ApiException;
import com.clawbotforall.miniapp.MiniappChatRoute;
import com.clawbotforall.miniapp.MiniappUserAccessService;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ExternalApiChatController {
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
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
  ) {
    ExternalApiChatRequest payload = request == null ? new ExternalApiChatRequest("", "", "", Map.of()) : request;
    String message = trim(payload.message());
    if (message.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空。");
    }
    String requestId = UUID.randomUUID().toString();
    MiniappChatRoute route = userAccessService.resolveChatRoute(authorization, payload.openid());
    String conversationId = trim(payload.conversationId()).isBlank() ? "default" : trim(payload.conversationId());
    String conversationHash = userAccessService.conversationHash(conversationId);

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    executor.execute(() -> {
      try {
        send(emitter, "start", Map.of(
            "requestId", requestId,
            "instanceId", route.instance().getId(),
            "conversationId", conversationId,
            "openVikingUserId", route.openvikingUserId()
        ));
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
            send(emitter, "delta", Map.of("text", text));
            sentDelta[0] = true;
          }
        });
        String text = stringify(result.get("text"));
        if (!sentDelta[0] && !text.isBlank()) {
          send(emitter, "delta", Map.of("text", text));
        }
        send(emitter, "done", Map.of(
            "requestId", requestId,
            "messageId", stringify(result.get("messageId")),
            "openVikingUserId", route.openvikingUserId(),
            "finishedAt", Instant.now().toString()
        ));
        emitter.complete();
      } catch (RuntimeException | IOException error) {
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

  public record ExternalApiChatRequest(
      String openid,
      String conversationId,
      String message,
      Map<String, Object> metadata
  ) {}
}
