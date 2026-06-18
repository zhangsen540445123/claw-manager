package com.clawbotforall.web;

import org.springframework.http.HttpStatus;

/**
 * 携带 HTTP 状态码和用户可见消息的应用异常。
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus getStatus() {
    return status;
  }
}
