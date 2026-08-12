package com.clawbotforall.web;

import com.clawbotforall.instance.InstanceDeletionService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将应用异常转换为统一 JSON 错误响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(InstanceDeletionService.InstanceDeleteConflictException.class)
  public ResponseEntity<Map<String, Object>> handleInstanceDeleteConflict(
      InstanceDeletionService.InstanceDeleteConflictException error) {
    return ResponseEntity
        .status(error.getStatus())
        .body(Map.of("error", error.getMessage(), "operation", error.getOperation()));
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, String>> handleApiException(ApiException error) {
    return ResponseEntity
        .status(error.getStatus())
        .body(Map.of("error", error.getMessage()));
  }
}
