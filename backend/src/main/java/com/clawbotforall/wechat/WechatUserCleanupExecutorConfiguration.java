package com.clawbotforall.wechat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WechatUserCleanupExecutorConfiguration {
  @Bean(name = "wechatUserCleanupExecutor", destroyMethod = "shutdown")
  public ExecutorService wechatUserCleanupExecutor() {
    AtomicInteger sequence = new AtomicInteger();
    ThreadFactory threadFactory = task -> {
      Thread thread = new Thread(task, "wechat-user-cleanup-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
    return new ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(100), threadFactory, new ThreadPoolExecutor.AbortPolicy());
  }
}
