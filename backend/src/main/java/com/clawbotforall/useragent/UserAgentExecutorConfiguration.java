package com.clawbotforall.useragent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserAgentExecutorConfiguration {
  static final String EXECUTOR_BEAN_NAME = "userAgentProvisioningExecutor";

  @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
  ExecutorService userAgentProvisioningExecutor() {
    AtomicInteger threadSequence = new AtomicInteger();
    ThreadFactory threadFactory = task -> {
      Thread thread = new Thread(task, "user-agent-provisioning-" + threadSequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
    return new ThreadPoolExecutor(
        2,
        4,
        60,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(100),
        threadFactory,
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
  }
}
