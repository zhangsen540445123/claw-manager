package com.clawbotforall.ws;

import java.time.Instant;

/**
 * 前端状态仓库共享的 WebSocket 事件信封。
 */
public record AppEvent<T>(
    String type,
    String traceId,
    Instant occurredAt,
    T payload
) {
  public static <T> AppEvent<T> of(String type, String traceId, T payload) {
    return new AppEvent<>(type, traceId, Instant.now(), payload);
  }
}
