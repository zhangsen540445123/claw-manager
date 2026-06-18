package com.clawbotforall.proxy;

import com.clawbotforall.auth.AuthenticatedAdmin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

/**
 * 注册实例 Control UI 流量的 WebSocket 代理路由。
 */
@Configuration
public class ControlUiWebSocketProxyConfig {

  @Bean
  HandlerMapping controlUiWebSocketProxyMapping(ControlUiWebSocketProxyHandler handler) {
    WebSocketHttpRequestHandler requestHandler = new WebSocketHttpRequestHandler(handler, new DefaultHandshakeHandler());
    requestHandler.setHandshakeInterceptors(List.of(new AuthenticatedAdminHandshakeInterceptor()));
    UpgradeOnlyHandlerMapping mapping = new UpgradeOnlyHandlerMapping();
    mapping.setUrlMap(Map.of("/proxy/**", requestHandler));
    mapping.setOrder(-1);
    return mapping;
  }

  static class UpgradeOnlyHandlerMapping implements HandlerMapping, Ordered {

    private Map<String, Object> urlMap = Map.of();
    private int order = Ordered.LOWEST_PRECEDENCE;

    @Override
    public HandlerExecutionChain getHandler(HttpServletRequest request) {
      if (!isWebSocketUpgrade(request)) {
        return null;
      }
      String path = pathWithinApplication(request);
      return urlMap.entrySet().stream()
          .filter(entry -> matches(entry.getKey(), path))
          .findFirst()
          .map(entry -> new HandlerExecutionChain(entry.getValue()))
          .orElse(null);
    }

    @Override
    public int getOrder() {
      return order;
    }

    void setUrlMap(Map<String, Object> urlMap) {
      this.urlMap = Map.copyOf(urlMap);
    }

    void setOrder(int order) {
      this.order = order;
    }

    static boolean isWebSocketUpgrade(HttpServletRequest request) {
      String upgrade = request.getHeader("Upgrade");
      String connection = request.getHeader("Connection");
      String key = request.getHeader("Sec-WebSocket-Key");
      return "websocket".equalsIgnoreCase(upgrade)
          && connection != null
          && connection.toLowerCase(java.util.Locale.ROOT).contains("upgrade")
          && key != null
          && !key.isBlank();
    }

    private static String pathWithinApplication(HttpServletRequest request) {
      String uri = request.getRequestURI();
      String contextPath = request.getContextPath();
      if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
        return uri.substring(contextPath.length());
      }
      return uri;
    }

    private static boolean matches(String pattern, String path) {
      if (pattern.endsWith("/**")) {
        String prefix = pattern.substring(0, pattern.length() - 3);
        return path.equals(prefix) || path.startsWith(prefix + "/");
      }
      return pattern.equals(path);
    }
  }

  static class AuthenticatedAdminHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 握手前把当前登录管理员写入 WebSocket 会话属性。
     */

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes
    ) {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedAdmin admin) {
        attributes.put(ControlUiWebSocketProxyHandler.AUTHENTICATED_ADMIN_ATTRIBUTE, admin);
      }
      return true;
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception
    ) {
      // 无需处理。
    }
  }
}
