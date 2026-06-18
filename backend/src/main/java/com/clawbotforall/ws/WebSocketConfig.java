package com.clawbotforall.ws;

import com.clawbotforall.auth.AuthenticatedAdmin;
import java.security.Principal;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * 配置 STOMP WebSocket 端点、Broker 主题和用户订阅规则。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final WebSocketAuthChannelInterceptor authChannelInterceptor;

  public WebSocketConfig(WebSocketAuthChannelInterceptor authChannelInterceptor) {
    this.authChannelInterceptor = authChannelInterceptor;
  }

  /**
   * 配置 STOMP Broker 目标地址。
   */

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue");
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  /**
   * 注册浏览器 STOMP 端点。
   */

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws")
        .setHandshakeHandler(new SecurityContextHandshakeHandler())
        .setAllowedOriginPatterns("*");
  }

  /**
   * 为客户端入站消息挂载登录态校验拦截器。
   */

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authChannelInterceptor);
  }

  static class SecurityContextHandshakeHandler extends DefaultHandshakeHandler {

    /**
     * 从 Spring Security 上下文或请求主体中解析 WebSocket 用户。
     */

    @Override
    protected Principal determineUser(
        ServerHttpRequest request,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes
    ) {
      AuthenticatedAdmin securityContextAdmin = authenticatedAdmin(SecurityContextHolder.getContext().getAuthentication());
      if (securityContextAdmin != null) {
        return securityContextAdmin;
      }
      Principal requestPrincipal = request.getPrincipal();
      if (requestPrincipal instanceof Authentication authentication) {
        AuthenticatedAdmin requestAdmin = authenticatedAdmin(authentication);
        if (requestAdmin != null) {
          return requestAdmin;
        }
      }
      if (requestPrincipal instanceof AuthenticatedAdmin admin) {
        return admin;
      }
      return super.determineUser(request, wsHandler, attributes);
    }

    private static AuthenticatedAdmin authenticatedAdmin(Authentication authentication) {
      if (authentication == null) {
        return null;
      }
      return authentication.getPrincipal() instanceof AuthenticatedAdmin admin ? admin : null;
    }
  }
}
