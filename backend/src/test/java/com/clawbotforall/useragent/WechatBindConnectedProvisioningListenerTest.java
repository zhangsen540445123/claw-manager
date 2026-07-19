package com.clawbotforall.useragent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clawbotforall.wechat.WechatBindConnectedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class WechatBindConnectedProvisioningListenerTest {

  @Mock
  UserAgentIdentityService identityService;

  @Mock
  UserAgentProvisioningService provisioningService;

  WechatBindConnectedProvisioningListener listener;
  Logger listenerLogger;
  ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    listener = new WechatBindConnectedProvisioningListener(identityService, provisioningService, Runnable::run);
    listenerLogger = (Logger) LoggerFactory.getLogger(WechatBindConnectedProvisioningListener.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    listenerLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    listenerLogger.detachAppender(logAppender);
    logAppender.stop();
  }

  @Test
  void provisionsPersistedIdentityForWechatOnlyBindAndRepeatedEventsRemainIdempotent() {
    WechatBindConnectedEvent event = new WechatBindConnectedEvent(
        "inst_1",
        "account_1",
        "wechat_sensitive_identity"
    );
    UserAgentIdentityResult identity = new UserAgentIdentityResult(
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        true
    );
    when(identityService.resolve("inst_1", "wechat_sensitive_identity")).thenReturn(identity);

    listener.onConnected(event);
    listener.onConnected(event);

    verify(identityService, times(2)).resolve("inst_1", "wechat_sensitive_identity");
    verify(provisioningService, times(2)).ensure(
        "inst_1",
        identity.agentId(),
        identity.openVikingUserId(),
        "account_1",
        "wechat_sensitive_identity"
    );
    verify(provisioningService, never()).ensureAsync(
        "inst_1",
        identity.agentId(),
        identity.openVikingUserId(),
        "account_1",
        "wechat_sensitive_identity"
    );
  }

  @Test
  void provisioningFailureDoesNotEscapeOrLogRawWechatIdentity() {
    String rawWechatUserId = "wechat_sensitive_identity";
    WechatBindConnectedEvent event = new WechatBindConnectedEvent("inst_1", "account_1", rawWechatUserId);
    when(identityService.resolve("inst_1", rawWechatUserId))
        .thenThrow(new IllegalStateException("identity failed"));

    assertThatCode(() -> listener.onConnected(event)).doesNotThrowAnyException();

    assertThat(logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
        .allSatisfy(message -> assertThat(message).doesNotContain(rawWechatUserId))
        .anySatisfy(message -> assertThat(message).contains("wechatUserHash=sha256:"));
  }
}
