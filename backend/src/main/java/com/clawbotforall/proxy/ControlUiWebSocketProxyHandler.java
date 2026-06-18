package com.clawbotforall.proxy;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.ProxyTarget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 桥接浏览器 WebSocket 会话和实例 Control UI 后端。
 */
@Component
public class ControlUiWebSocketProxyHandler extends AbstractWebSocketHandler {

  static final String AUTHENTICATED_ADMIN_ATTRIBUTE = "controlUiProxyAuthenticatedAdmin";
  private static final Pattern INSTANCE_PATH = Pattern.compile(".*/proxy/([^/]+)(?:/.*)?$");

  private final InstanceCommandService instanceCommandService;
  private final OpenClawRuntime openClawRuntime;
  private final HttpClient httpClient;
  private final Map<String, WebSocket> upstreams = new ConcurrentHashMap<>();

  public ControlUiWebSocketProxyHandler(
      InstanceCommandService instanceCommandService,
      OpenClawRuntime openClawRuntime
  ) {
    this.instanceCommandService = instanceCommandService;
    this.openClawRuntime = openClawRuntime;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  /**
   * 浏览器连接时打开上游 Control UI WebSocket。
   */

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    Optional<String> instanceId = instanceId(session);
    if (instanceId.isEmpty()) {
      session.close(CloseStatus.BAD_DATA.withReason("实例路径无效。"));
      return;
    }
    Optional<AuthenticatedAdmin> authenticatedAdmin = authenticatedAdmin(session);
    if (authenticatedAdmin.isEmpty()) {
      session.close(CloseStatus.NOT_ACCEPTABLE.withReason("请先登录。"));
      return;
    }

    InstanceEntity instance = instanceCommandService.requireInstance(instanceId.get());

    ProxyTarget target = openClawRuntime.resolveProxyTarget(instance);
    URI targetUri = ProxyUris.wsTargetUri(instance.getId(), session.getUri(), target);
    WebSocket.Builder upstreamBuilder = httpClient.newWebSocketBuilder()
        .header("Authorization", "Bearer " + instance.getGatewayToken());
    String origin = session.getHandshakeHeaders().getOrigin();
    if (origin != null && !origin.isBlank()) {
      upstreamBuilder.header("Origin", origin);
    }
    WebSocket upstream = upstreamBuilder
        .buildAsync(targetUri, new RelayListener(session))
        .join();
    upstream.request(1);
    upstreams.put(session.getId(), upstream);
  }

  /**
   * 将浏览器文本 WebSocket 消息转发到上游 Control UI。
   */

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    WebSocket upstream = upstreams.get(session.getId());
    if (upstream != null) {
      upstream.sendText(message.getPayload(), message.isLast());
    }
  }

  /**
   * 将浏览器二进制 WebSocket 消息转发到上游 Control UI。
   */

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    WebSocket upstream = upstreams.get(session.getId());
    if (upstream != null) {
      upstream.sendBinary(message.getPayload(), message.isLast());
    }
  }

  /**
   * 将浏览器心跳响应消息转发到上游 Control UI。
   */

  @Override
  protected void handlePongMessage(WebSocketSession session, PongMessage message) {
    WebSocket upstream = upstreams.get(session.getId());
    if (upstream != null) {
      upstream.sendPong(message.getPayload());
    }
  }

  /**
   * 浏览器传输异常时关闭上下游连接。
   */

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    closeUpstream(session);
    if (session.isOpen()) {
      session.close(CloseStatus.SERVER_ERROR);
    }
  }

  /**
   * 浏览器会话结束时关闭上游 WebSocket。
   */

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    closeUpstream(session);
  }

  private void closeUpstream(WebSocketSession session) {
    WebSocket upstream = upstreams.remove(session.getId());
    if (upstream != null) {
      upstream.sendClose(WebSocket.NORMAL_CLOSURE, "downstream closed");
    }
  }

  private Optional<String> instanceId(WebSocketSession session) {
    URI uri = session.getUri();
    if (uri == null) {
      return Optional.empty();
    }
    Matcher matcher = INSTANCE_PATH.matcher(uri.getPath());
    return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  private Optional<AuthenticatedAdmin> authenticatedAdmin(WebSocketSession session) {
    Object attributeAdmin = session.getAttributes().get(AUTHENTICATED_ADMIN_ATTRIBUTE);
    if (attributeAdmin instanceof AuthenticatedAdmin admin) {
      return Optional.of(admin);
    }
    if (session.getPrincipal() instanceof org.springframework.security.core.Authentication authentication
        && authentication.getPrincipal() instanceof AuthenticatedAdmin admin) {
      return Optional.of(admin);
    }
    if (session.getPrincipal() instanceof AuthenticatedAdmin admin) {
      return Optional.of(admin);
    }
    return Optional.empty();
  }

  private static final class RelayListener implements WebSocket.Listener {

    private final WebSocketSession downstream;
    private final StringBuilder textBuffer = new StringBuilder();
    private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

    private RelayListener(WebSocketSession downstream) {
      this.downstream = downstream;
    }

    /**
     * 接收上游文本分片，并在完整消息到达后转发给浏览器。
     */

    @Override
    public synchronized CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      textBuffer.append(data);
      if (last) {
        sendDownstream(new TextMessage(textBuffer.toString()));
        textBuffer.setLength(0);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    /**
     * 接收上游二进制分片，并在完整消息到达后转发给浏览器。
     */

    @Override
    public synchronized CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      byte[] chunk = new byte[data.remaining()];
      data.get(chunk);
      binaryBuffer.writeBytes(chunk);
      if (last) {
        sendDownstream(new BinaryMessage(binaryBuffer.toByteArray()));
        binaryBuffer.reset();
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    /**
     * 响应上游心跳请求，保持代理连接活跃。
     */

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
      webSocket.request(1);
      return webSocket.sendPong(message);
    }

    /**
     * 上游关闭时同步关闭浏览器 WebSocket。
     */

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      try {
        if (downstream.isOpen()) {
          downstream.close(new CloseStatus(statusCode, reason));
        }
      } catch (IOException ignored) {
        // 尽力关闭连接。
      }
      return CompletableFuture.completedFuture(null);
    }

    /**
     * 上游连接异常时关闭浏览器 WebSocket。
     */

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      try {
        if (downstream.isOpen()) {
          downstream.close(CloseStatus.SERVER_ERROR);
        }
      } catch (IOException ignored) {
        // 尽力关闭连接。
      }
    }

    private void sendDownstream(org.springframework.web.socket.WebSocketMessage<?> message) {
      try {
        synchronized (downstream) {
          if (downstream.isOpen()) {
            downstream.sendMessage(message);
          }
        }
      } catch (IOException ignored) {
        // 下游关闭路径会清理上游连接。
      }
    }
  }
}
