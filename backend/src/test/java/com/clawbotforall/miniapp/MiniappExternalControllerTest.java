package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clawbotforall.wechat.PublicWechatBindLink;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MiniappExternalControllerTest {

  @Mock
  MiniappHmacAuthService authService;

  @Mock
  MiniappBindingService bindingService;

  @Mock
  MiniappUserAccessService userAccessService;

  MockMvc mockMvc;
  Logger controllerLogger;
  ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    controllerLogger = (Logger) LoggerFactory.getLogger(MiniappExternalController.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    controllerLogger.addAppender(logAppender);
    mockMvc = MockMvcBuilders.standaloneSetup(new MiniappExternalController(
        authService,
        bindingService,
        userAccessService,
        new ObjectMapper()
    )).build();
  }

  @AfterEach
  void tearDown() {
    controllerLogger.detachAppender(logAppender);
    logAppender.stop();
  }

  @Test
  void bindLinkSuccessLogsDoNotExposeRawTokenOrUserIdentities() throws Exception {
    String rawOpenid = "openid_1";
    String rawToken = "wbl_sensitive_token";
    String rawOpenVikingUserId = "wx_sensitive_identity";
    when(bindingService.createWechatBindLink(rawOpenid, "")).thenReturn(new MiniappBindLinkResult(
        rawOpenid,
        rawToken,
        "waiting_scan",
        "inst_1",
        rawOpenVikingUserId,
        false,
        null
    ));
    when(bindingService.getBindLink(rawToken, "")).thenReturn(new MiniappBindLinkResult(
        rawOpenid,
        rawToken,
        "connected",
        "inst_1",
        rawOpenVikingUserId,
        true,
        null
    ));

    mockMvc.perform(post("/api/external/miniapp/wechat-bind-links")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-CM-App-Id", "miniapp_main")
            .header("X-CM-Timestamp", "1783159200000")
            .header("X-CM-Nonce", "nonce_log_1")
            .header("X-CM-Signature", "signature")
            .content("{\"openid\":\"openid_1\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/external/miniapp/wechat-bind-links/" + rawToken)
            .header("X-CM-App-Id", "miniapp_main")
            .header("X-CM-Timestamp", "1783159200000")
            .header("X-CM-Nonce", "nonce_log_2")
            .header("X-CM-Signature", "signature"))
        .andExpect(status().isOk());

    List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(messages).allSatisfy(message -> {
      assertThat(message).doesNotContain(rawOpenid);
      assertThat(message).doesNotContain(rawToken);
      assertThat(message).doesNotContain(rawOpenVikingUserId);
    });
    assertThat(messages).anySatisfy(message -> assertThat(message).contains("bindTokenPresent=present"));
  }

  @Test
  void createWechatBindLinkReturnsCorrelationHeaderWithoutChangingBody() throws Exception {
    String body = "{\"openid\":\"openid_1\"}";
    when(bindingService.createWechatBindLink("openid_1", "")).thenReturn(new MiniappBindLinkResult(
        "openid_1",
        "wbl_token",
        "waiting_scan",
        "inst_1",
        "",
        false,
        link()
    ));

    MvcResult result = mockMvc.perform(post("/api/external/miniapp/wechat-bind-links")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-CM-App-Id", "miniapp_main")
            .header("X-CM-Timestamp", "1783159200000")
            .header("X-CM-Nonce", "nonce_1")
            .header("X-CM-Signature", "signature")
            .content(body))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-CM-Request-Id"))
        .andExpect(jsonPath("$.binding.openid").value("openid_1"))
        .andExpect(jsonPath("$.binding.bindToken").value("wbl_token"))
        .andExpect(jsonPath("$.binding.qrLink").value("https://qr.example.test"))
        .andReturn();

    assertThat(result.getResponse().getHeader("X-CM-Request-Id")).startsWith("cmreq_");
    verify(authService).requireAuthorized(
        eq("POST"),
        eq("/api/external/miniapp/wechat-bind-links"),
        eq(body),
        any(MiniappHmacHeaders.class)
    );
  }

  @Test
  void getWechatBindLinkReturnsCorrelationHeader() throws Exception {
    when(bindingService.getBindLink("wbl_token", "")).thenReturn(new MiniappBindLinkResult(
        "openid_1",
        "wbl_token",
        "initializing",
        "inst_1",
        "",
        false,
        null
    ));

    mockMvc.perform(get("/api/external/miniapp/wechat-bind-links/wbl_token")
            .header("X-CM-App-Id", "miniapp_main")
            .header("X-CM-Timestamp", "1783159200000")
            .header("X-CM-Nonce", "nonce_2")
            .header("X-CM-Signature", "signature"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-CM-Request-Id"))
        .andExpect(jsonPath("$.binding.status").value("initializing"))
        .andExpect(jsonPath("$.binding.canCreateUserKey").value(false))
        .andExpect(jsonPath("$.binding.openVikingUserId").value(""));
  }

  @Test
  void createUserKeyReturnsCorrelationHeaderAndDoesNotExposeBodyShapeChanges() throws Exception {
    when(userAccessService.createOrGetUserKey("openid_1", false)).thenReturn(new MiniappUserKeyResult(
        "openid_1",
        "cm_user_secret",
        "cm_user_sec...cret",
        "wx_123",
        "inst_1",
        true
    ));

    mockMvc.perform(post("/api/external/miniapp/user-keys")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-CM-App-Id", "miniapp_main")
            .header("X-CM-Timestamp", "1783159200000")
            .header("X-CM-Nonce", "nonce_3")
            .header("X-CM-Signature", "signature")
            .content("{\"openid\":\"openid_1\",\"reset\":false}"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-CM-Request-Id"))
        .andExpect(jsonPath("$.userKey.key").value("cm_user_secret"))
        .andExpect(jsonPath("$.userKey.keyPreview").value("cm_user_sec...cret"));
  }

  private static PublicWechatBindLink link() {
    return new PublicWechatBindLink(
        "wbl_token",
        "new",
        "waiting_scan",
        "",
        "inst_1",
        "实例 1",
        "link",
        "",
        "https://qr.example.test",
        "2026-07-04T10:05:00Z",
        false,
        "",
        "2026-07-04T10:10:00Z",
        null,
        "2026-07-04T10:00:00Z",
        "2026-07-04T10:00:00Z",
        "待扫码",
        "新绑定",
        "https://bind.example.test"
    );
  }
}
