package com.clawbotforall.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clawbotforall.instance.InstanceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ExternalApiChatControllerTest {

  @Mock
  ExternalApiSettingsService settingsService;

  @Mock
  ExternalApiRouteService routeService;

  @Mock
  ExternalApiQueueService queueService;

  MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders
        .standaloneSetup(new ExternalApiChatController(settingsService, routeService, queueService))
        .build();
  }

  @Test
  void queueFailureEmitsSseErrorEvent() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    ExternalApiResolvedRoute route = new ExternalApiResolvedRoute(
        instance,
        "f9db8c63722f76a920d852d85f502177",
        "api_f9db8c63722f76a920d852d85f502177",
        "api:f9db8c63722f76a920d852d85f502177"
    );
    doNothing().when(settingsService).requireAuthorized("Bearer test-key");
    when(routeService.resolveOrCreateRoute("local-test-user-001")).thenReturn(route);
    when(routeService.conversationHash("conv_1")).thenReturn("conversationhash");
    when(queueService.streamApiChannelMessage(eq(instance), anyMap(), any()))
        .thenThrow(new IllegalStateException("queue timeout"));

    MvcResult result = mockMvc.perform(post("/api/external/openclaw/chat/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "openid": "local-test-user-001",
                  "conversationId": "conv_1",
                  "message": "hello"
                }
                """))
        .andExpect(request().asyncStarted())
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
  }

  @Test
  void streamChatEmitsMultipleDeltaEventsBeforeDone() throws Exception {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    ExternalApiResolvedRoute route = new ExternalApiResolvedRoute(
        instance,
        "f9db8c63722f76a920d852d85f502177",
        "api_f9db8c63722f76a920d852d85f502177",
        "api:f9db8c63722f76a920d852d85f502177"
    );
    doNothing().when(settingsService).requireAuthorized("Bearer test-key");
    when(routeService.resolveOrCreateRoute("local-test-user-001")).thenReturn(route);
    when(routeService.conversationHash("conv_1")).thenReturn("conversationhash");
    when(queueService.streamApiChannelMessage(eq(instance), anyMap(), any()))
        .thenAnswer(invocation -> {
          ExternalApiQueueService.StreamDeltaConsumer onDelta = invocation.getArgument(2);
          onDelta.accept("你");
          onDelta.accept("好");
          return java.util.Map.of(
              "ok", true,
              "requestId", "req_1",
              "messageId", "msg_1",
              "text", "你好"
          );
        });

    MvcResult result = mockMvc.perform(post("/api/external/openclaw/chat/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "openid": "local-test-user-001",
                  "conversationId": "conv_1",
                  "message": "hello"
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
    int firstDeltaIndex = body.indexOf("event:delta");
    int secondDeltaIndex = body.indexOf("event:delta", firstDeltaIndex + 1);
    int doneIndex = body.indexOf("event:done");
    assertThat(startIndex).isGreaterThanOrEqualTo(0);
    assertThat(firstDeltaIndex).isGreaterThan(startIndex);
    assertThat(secondDeltaIndex).isGreaterThan(firstDeltaIndex);
    assertThat(doneIndex).isGreaterThan(secondDeltaIndex);
  }
}
