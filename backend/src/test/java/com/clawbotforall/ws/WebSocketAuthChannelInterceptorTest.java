package com.clawbotforall.ws;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clawbotforall.auth.AuthenticatedAdmin;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

class WebSocketAuthChannelInterceptorTest {

  private final WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor();

  @Test
  void rejectsAnonymousConnect() {
    Message<byte[]> message = message(StompCommand.CONNECT, null, null);

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("请先登录");
  }

  @Test
  void rejectsAnonymousSubscriptionsToAdminTopics() {
    Message<byte[]> message = message(StompCommand.SUBSCRIBE, "/topic/admin/instances", null);

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("请先登录");
  }

  @Test
  void allowsAdminSubscriptionsToAdminTopics() {
    Message<byte[]> message = message(StompCommand.SUBSCRIBE, "/topic/admin/instances", admin());

    assertThatCode(() -> interceptor.preSend(message, null)).doesNotThrowAnyException();
  }

  private static Message<byte[]> message(StompCommand command, String destination, AuthenticatedAdmin admin) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setDestination(destination);
    accessor.setUser(admin);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private static AuthenticatedAdmin admin() {
    return new AuthenticatedAdmin(
        "admin_123",
        "admin@example.com",
        "tester",
        false,
        "2026-06-15T00:00:00Z",
        "2026-06-15T00:00:00Z"
    );
  }
}
