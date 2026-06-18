package com.clawbotforall.ws;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 向 STOMP 主题发送应用事件。
 */
@Component
public class AppEventPublisher {

  private final SimpMessagingTemplate messagingTemplate;

  public AppEventPublisher(SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  /**
   * 向指定用户的 STOMP 队列发送应用事件。
   */

  public void sendToUser(String userId, String destination, AppEvent<?> event) {
    messagingTemplate.convertAndSendToUser(userId, destination, event);
  }

  /**
   * 向 STOMP 主题发布应用事件。
   */

  public void sendToTopic(String destination, AppEvent<?> event) {
    messagingTemplate.convertAndSend(destination, event);
  }
}
