package com.clawbotforall.useragent;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clawbotforall.auth.SessionAuthenticationFilter;
import com.clawbotforall.auth.SessionService;
import com.clawbotforall.config.SecurityConfig;
import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserAgentIdentityController.class)
@Import({SecurityConfig.class, SessionAuthenticationFilter.class})
class UserAgentIdentitySecurityTest {

  @Autowired
  MockMvc mockMvc;

  @MockBean
  SessionService sessionService;

  @MockBean
  OpenVikingBrokerTokenService tokenService;

  @MockBean
  UserAgentIdentityService identityService;

  @Test
  void permitsInternalResolverThroughRealSecurityFilterChain() throws Exception {
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
        .andExpect(jsonPath("$.agentId").value("user_0123456789abcdef0123456789abcdef"));
  }
}
