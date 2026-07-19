package com.clawbotforall.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.miniapp.MiniappChatRoute;
import com.clawbotforall.miniapp.MiniappUserAccessService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@ExtendWith(MockitoExtension.class)
class ExternalApiChatControllerTest {

  @Mock
  MiniappUserAccessService userAccessService;

  @Mock
  ExternalApiQueueService queueService;

  MockMvc mockMvc;
  ExecutorService streamExecutor;
  ScheduledExecutorService heartbeatExecutor;
  Logger controllerLogger;
  ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    controllerLogger = (Logger) LoggerFactory.getLogger(ExternalApiChatController.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    controllerLogger.addAppender(logAppender);
    streamExecutor = Executors.newSingleThreadExecutor();
    heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    mockMvc = MockMvcBuilders
        .standaloneSetup(new ExternalApiChatController(
            userAccessService,
            queueService,
            streamExecutor,
            heartbeatExecutor,
            Duration.ofMillis(10),
            Duration.ofSeconds(5)))
        .build();
  }

  @AfterEach
  void tearDown() {
    controllerLogger.detachAppender(logAppender);
    logAppender.stop();
    streamExecutor.shutdownNow();
    heartbeatExecutor.shutdownNow();
  }

  @Test
  void chatLifecycleLogsDoNotExposeRawOpenidOrOpenVikingIdentity() throws Exception {
    String rawOpenid = "openid_1";
    String rawOpenVikingUserId = "wx_sensitive_identity";
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    MiniappChatRoute route = new MiniappChatRoute(
        instance,
        rawOpenid,
        "openid_hash_1",
        "user_0123456789abcdef0123456789abcdef",
        rawOpenVikingUserId,
        "miniapp:openid_hash_1"
    );
    when(userAccessService.resolveChatRoute("Bearer cm_user_secret", rawOpenid)).thenReturn(route);
    when(userAccessService.conversationHash("conv_1")).thenReturn("conversationhash");
    when(queueService.streamApiChannelMessage(eq(instance), anyMap(), any(), any()))
        .thenReturn(java.util.Map.of("ok", true, "messageId", "msg_1", "text", "完成"));

    MvcResult result = mockMvc.perform(post("/api/external/openclaw/chat/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer cm_user_secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "openid": "openid_1",
                  "conversationId": "conv_1",
                  "message": "hello"
                }
                """))
        .andExpect(request().asyncStarted())
        .andReturn();
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

    assertThat(logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
        .allSatisfy(message -> {
          assertThat(message).doesNotContain(rawOpenid);
          assertThat(message).doesNotContain(rawOpenVikingUserId);
        });
  }

  @Test
  void queueFailureEmitsSseErrorEvent() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    MiniappChatRoute route = new MiniappChatRoute(
        instance,
        "local-test-user-001",
        "f9db8c63722f76a920d852d85f502177",
        "user_0123456789abcdef0123456789abcdef",
        "wx_f9db8c63722f76a920d852d85f502177",
        "miniapp:f9db8c63722f76a920d852d85f502177"
    );
    when(userAccessService.resolveChatRoute("Bearer cm_user_secret", "local-test-user-001")).thenReturn(route);
    when(userAccessService.conversationHash("conv_1")).thenReturn("conversationhash");
    when(queueService.streamApiChannelMessage(eq(instance), anyMap(), any(), any()))
        .thenThrow(new IllegalStateException("queue timeout"));

    MvcResult result = mockMvc.perform(post("/api/external/openclaw/chat/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer cm_user_secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "openid": "local-test-user-001",
                  "conversationId": "conv_1",
                  "message": "hello"
                }
                """))
        .andExpect(request().asyncStarted())
        .andExpect(header().exists("X-CM-Request-Id"))
        .andReturn();

    String body = mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();

    assertThat(body).contains("event:start");
    assertThat(body).contains("event:error");
    assertThat(body).contains("OPENCLAW_API_CHANNEL_ERROR");
    assertThat(body).contains("queue timeout");
    assertThat(body).contains("wx_f9db8c63722f76a920d852d85f502177");
  }

  @Test
  void streamChatEmitsMultipleDeltaEventsBeforeDone() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    MiniappChatRoute route = new MiniappChatRoute(
        instance,
        "local-test-user-001",
        "f9db8c63722f76a920d852d85f502177",
        "user_0123456789abcdef0123456789abcdef",
        "wx_f9db8c63722f76a920d852d85f502177",
        "miniapp:f9db8c63722f76a920d852d85f502177"
    );
    when(userAccessService.resolveChatRoute("Bearer cm_user_secret", "local-test-user-001")).thenReturn(route);
    when(userAccessService.conversationHash("conv_1")).thenReturn("conversationhash");
    when(queueService.streamApiChannelMessage(eq(instance), anyMap(), any(), any()))
        .thenAnswer(invocation -> {
          java.util.Map<String, Object> params = invocation.getArgument(1);
          assertThat(params.get("agentId")).isEqualTo("user_0123456789abcdef0123456789abcdef");
          ExternalApiQueueService.StreamDeltaConsumer onDelta = invocation.getArgument(2);
          ExternalApiQueueService.StreamArtifactConsumer onArtifact = invocation.getArgument(3);
          onDelta.accept("你");
          onDelta.accept("好");
          onArtifact.accept(java.util.Map.of(
              "id", "artifact-1",
              "type", "image_report",
              "miniappPath", "/pages/html-viewer/index?contentKey=x"
          ));
          return java.util.Map.of(
              "ok", true,
              "requestId", "req_1",
              "messageId", "msg_1",
              "text", "你好"
          );
        });

    MvcResult result = mockMvc.perform(post("/api/external/openclaw/chat/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer cm_user_secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "openid": "local-test-user-001",
                  "conversationId": "conv_1",
                  "message": "hello"
                }
                """))
        .andExpect(request().asyncStarted())
        .andExpect(header().exists("X-CM-Request-Id"))
        .andReturn();

    String body = mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();

    int startIndex = body.indexOf("event:start");
    int firstDeltaIndex = body.indexOf("event:delta");
    int secondDeltaIndex = body.indexOf("event:delta", firstDeltaIndex + 1);
    int artifactIndex = body.indexOf("event:artifact");
    int doneIndex = body.indexOf("event:done");
    assertThat(startIndex).isGreaterThanOrEqualTo(0);
    assertThat(firstDeltaIndex).isGreaterThan(startIndex);
    assertThat(secondDeltaIndex).isGreaterThan(firstDeltaIndex);
    assertThat(artifactIndex).isGreaterThan(secondDeltaIndex);
    assertThat(doneIndex).isGreaterThan(artifactIndex);
    assertThat(body).contains("wx_f9db8c63722f76a920d852d85f502177");
  }

  @Test
  void streamChatEmitsHeartbeatWhileWaitingForQueueProgress() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    MiniappChatRoute route = new MiniappChatRoute(
        instance,
        "local-test-user-001",
        "f9db8c63722f76a920d852d85f502177",
        "user_0123456789abcdef0123456789abcdef",
        "wx_f9db8c63722f76a920d852d85f502177",
        "miniapp:f9db8c63722f76a920d852d85f502177"
    );
    when(userAccessService.resolveChatRoute("Bearer cm_user_secret", "local-test-user-001")).thenReturn(route);
    when(userAccessService.conversationHash("conv_1")).thenReturn("conversationhash");
    when(queueService.streamApiChannelMessage(eq(instance), anyMap(), any(), any()))
        .thenAnswer(invocation -> {
          Thread.sleep(45);
          ExternalApiQueueService.StreamDeltaConsumer onDelta = invocation.getArgument(2);
          onDelta.accept("完成");
          return java.util.Map.of(
              "ok", true,
              "requestId", "req_heartbeat",
              "messageId", "msg_heartbeat",
              "text", "完成"
          );
        });

    MvcResult result = mockMvc.perform(post("/api/external/openclaw/chat/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer cm_user_secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "openid": "local-test-user-001",
                  "conversationId": "conv_1",
                  "message": "生成图片"
                }
                """))
        .andExpect(request().asyncStarted())
        .andReturn();

    String body = mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();

    int startIndex = body.indexOf("event:start");
    int heartbeatIndex = body.indexOf("event:heartbeat");
    int doneIndex = body.indexOf("event:done");
    assertThat(startIndex).isGreaterThanOrEqualTo(0);
    assertThat(heartbeatIndex).isGreaterThan(startIndex);
    assertThat(doneIndex).isGreaterThan(heartbeatIndex);
    assertThat(body.substring(doneIndex)).doesNotContain("event:heartbeat");
  }

  @Test
  void springContextUsesTheProductionConstructorWhenTestConstructorAlsoExists() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(MiniappUserAccessService.class, () -> userAccessService);
      context.registerBean(ExternalApiQueueService.class, () -> queueService);
      context.register(ExternalApiChatController.class);

      context.refresh();

      assertThat(context.getBean(ExternalApiChatController.class)).isNotNull();
    }
  }
}
