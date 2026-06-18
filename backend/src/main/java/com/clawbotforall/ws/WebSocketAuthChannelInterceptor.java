package com.clawbotforall.ws;

import com.clawbotforall.auth.AuthenticatedAdmin;
import java.security.Principal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 使用认证管理员主体校验 STOMP 订阅权限。
 */
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    Message<?> currentMessage = message;
    if (accessor == null || !accessor.isMutable()) {
      accessor = StompHeaderAccessor.wrap(message);
      accessor.setLeaveMutable(true);
      currentMessage = MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }
    StompCommand command = accessor.getCommand();
    if (command == null) {
      return currentMessage;
    }

    if (requiresAuthenticatedAdmin(command) && authenticatedAdmin(accessor.getUser()) == null) {
      throw new AccessDeniedException("请先登录。");
    }

    if (command == StompCommand.SUBSCRIBE && isAdminDestination(accessor.getDestination())) {
      if (authenticatedAdmin(accessor.getUser()) == null) {
        throw new AccessDeniedException("需要管理员权限。");
      }
    }

    return currentMessage;
  }

  private static boolean requiresAuthenticatedAdmin(StompCommand command) {
    return command == StompCommand.CONNECT
        || command == StompCommand.SUBSCRIBE
        || command == StompCommand.SEND;
  }

  private static boolean isAdminDestination(String destination) {
    return destination != null && destination.startsWith("/topic/admin/");
  }

  private static AuthenticatedAdmin authenticatedAdmin(Principal principal) {
    return principal instanceof AuthenticatedAdmin admin ? admin : null;
  }
}
