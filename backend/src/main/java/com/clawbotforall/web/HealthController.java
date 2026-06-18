package com.clawbotforall.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供用于就绪检查的轻量健康接口。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

  /**
   * 返回服务健康检查结果。
   */

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of(
        "ok", true,
        "service", "claw-manager-api",
        "timestamp", Instant.now().toString()
    );
  }
}
