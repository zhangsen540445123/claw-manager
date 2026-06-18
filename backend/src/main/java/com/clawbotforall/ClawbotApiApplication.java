package com.clawbotforall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 本项目后端 API 服务的 Spring Boot 应用入口。
 */
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class ClawbotApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(ClawbotApiApplication.class, args);
  }
}
