package com.clawbotforall.useragent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UserAgentExecutorConfigurationTest {

  @Test
  void runsOverflowTaskInCallerInsteadOfDroppingProvisioning() {
    ExecutorService service = new UserAgentExecutorConfiguration().userAgentProvisioningExecutor();
    ThreadPoolExecutor executor = (ThreadPoolExecutor) service;
    CountDownLatch blocker = new CountDownLatch(1);
    try {
      for (int index = 0; index < 104; index += 1) {
        executor.execute(() -> await(blocker));
      }
      AtomicReference<String> executionThread = new AtomicReference<>();
      String callerThread = Thread.currentThread().getName();

      executor.execute(() -> executionThread.set(Thread.currentThread().getName()));

      assertThat(executionThread.get()).isEqualTo(callerThread);
    } finally {
      blocker.countDown();
      service.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }
}
