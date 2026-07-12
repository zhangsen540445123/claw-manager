package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.clawbotforall.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class MiniappBridgeServiceTest {

  @Mock MiniappUserBindingMapper bindingMapper;
  @Mock MiniappUserKeyMapper keyMapper;

  private MockRestServiceServer server;
  private MiniappBridgeService service;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    service = new MiniappBridgeService(bindingMapper, keyMapper, builder, "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC), new MiniappBridgeActionRegistry());
    lenient().when(bindingMapper.findByWechatUserId("wechat-1")).thenReturn(binding());
    when(keyMapper.findByOpenidHash("hash-1")).thenReturn(key());
  }

  @Test
  void sendsNormalizedGoalCreateRequestWithIdentityHeaders() {
    server.expect(requestTo("https://miniapp.example/api/open-api/goals"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Open-Api-Openid", "openid-1"))
        .andExpect(header("Authorization", "Bearer cm_user_secret"))
        .andExpect(header("X-CM-Bridge-Request-Id", "mbreq-1"))
        .andExpect(jsonPath("$.goalArea").value("学习·成长"))
        .andExpect(jsonPath("$.userTags").value("study"))
        .andExpect(jsonPath("$.category").doesNotExist())
        .andRespond(withSuccess("{\"code\":200,\"data\":{\"id\":12}}", MediaType.APPLICATION_JSON));

    service.execute("goal_create", new MiniappBridgeRequest("instance-1", "wechat-1", Map.of(
        "title", "学习英语", "goalType", "YEAR", "goalYear", 2026,
        "goalCategory", "PROJECT", "category", "study"), "mbreq-1"));

    server.verify();
    verify(keyMapper).updateLastUsed("hash-1", "2026-07-12T00:00:00Z");
  }

  @Test
  void treatsBusinessErrorInsideHttp200AsToolFailure() {
    server.expect(requestTo("https://miniapp.example/api/open-api/daily-checklist?date=2026-07-12"))
        .andRespond(withSuccess("{\"code\":400,\"message\":\"日期无效\"}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> service.execute("daily_checklist", new MiniappBridgeRequest(
        "instance-1", "wechat-1", Map.of("date", "2026-07-12"), "mbreq-2")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("日期无效");
  }

  @Test
  void resolvesMiniappSenderByOpenidHash() {
    when(bindingMapper.findByOpenidHash("hash-1")).thenReturn(binding());
    server.expect(requestTo("https://miniapp.example/api/open-api/goals/statistics"))
        .andRespond(withSuccess("{\"code\":200,\"data\":{}}", MediaType.APPLICATION_JSON));

    service.execute("goal_statistics", new MiniappBridgeRequest(
        "instance-1", "miniapp:hash-1", Map.of(), "mbreq-3"));

    server.verify();
  }

  private MiniappUserBindingEntity binding() {
    MiniappUserBindingEntity binding = new MiniappUserBindingEntity();
    binding.setOpenidHash("hash-1");
    binding.setOpenid("openid-1");
    binding.setInstanceId("instance-1");
    binding.setWechatUserId("wechat-1");
    binding.setBindStatus("connected");
    return binding;
  }

  private MiniappUserKeyEntity key() {
    MiniappUserKeyEntity key = new MiniappUserKeyEntity();
    key.setOpenidHash("hash-1");
    key.setOpenid("openid-1");
    key.setUserKey("cm_user_secret");
    key.setEnabled(true);
    return key;
  }
}
