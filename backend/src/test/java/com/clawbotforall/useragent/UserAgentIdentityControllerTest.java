package com.clawbotforall.useragent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import com.clawbotforall.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UserAgentIdentityControllerTest {

  @Mock
  OpenVikingBrokerTokenService tokenService;

  @Mock
  UserAgentIdentityService identityService;

  UserAgentIdentityController controller;
  MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    controller = new UserAgentIdentityController(tokenService, identityService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void requiresExistingOpenVikingBrokerToken() {
    when(tokenService.matches("bad")).thenReturn(false);

    assertThatThrownBy(() -> controller.resolve(
        new UserAgentResolveRequest("inst_1", "wechat_user_1"),
        "Bearer bad"
    ))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("token 无效");
  }

  @Test
  void returnsResolvedIdentityAtResponseRoot() throws Exception {
    when(tokenService.matches("broker-token")).thenReturn(true);
    when(identityService.resolve("inst_1", "wechat_user_1")).thenReturn(
        new UserAgentIdentityResult(
            "user_0123456789abcdef0123456789abcdef",
            "wx_0123456789abcdef0123456789abcdef",
            true
        )
    );

    mockMvc.perform(post("/api/internal/user-agents/resolve")
            .header(HttpHeaders.AUTHORIZATION, "Bearer broker-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"instanceId":"inst_1","wechatUserId":"wechat_user_1"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agentId").value("user_0123456789abcdef0123456789abcdef"))
        .andExpect(jsonPath("$.openVikingUserId").value("wx_0123456789abcdef0123456789abcdef"))
        .andExpect(jsonPath("$.created").value(true));

    verify(identityService).resolve("inst_1", "wechat_user_1");
  }

  @Test
  void rejectsUnknownRequestFields() throws Exception {
    mockMvc.perform(post("/api/internal/user-agents/resolve")
            .header(HttpHeaders.AUTHORIZATION, "Bearer broker-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"instanceId":"inst_1","wechatUserId":"wechat_user_1","openid":"must-not-be-accepted"}
                """))
        .andExpect(status().isBadRequest());
  }
}
