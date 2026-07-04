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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new MiniappExternalController(
        authService,
        bindingService,
        userAccessService,
        new ObjectMapper()
    )).build();
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
        "connected",
        "inst_1",
        "wx_123",
        true,
        null
    ));

    mockMvc.perform(get("/api/external/miniapp/wechat-bind-links/wbl_token")
            .header("X-CM-App-Id", "miniapp_main")
            .header("X-CM-Timestamp", "1783159200000")
            .header("X-CM-Nonce", "nonce_2")
            .header("X-CM-Signature", "signature"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-CM-Request-Id"))
        .andExpect(jsonPath("$.binding.openVikingUserId").value("wx_123"));
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
