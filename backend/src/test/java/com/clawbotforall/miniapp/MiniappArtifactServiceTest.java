package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.clawbotforall.trace.IntegrationTraceService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class MiniappArtifactServiceTest {
  @Mock MiniappUserBindingMapper bindingMapper;
  @Mock MiniappUserKeyMapper keyMapper;
  @Mock IntegrationTraceService traces;
  private MockRestServiceServer server;
  private MiniappArtifactService service;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    service = new MiniappArtifactService(bindingMapper, keyMapper, builder, "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces);
    lenient().when(bindingMapper.findByWechatUserId("wechat-1")).thenReturn(binding());
    when(keyMapper.findByOpenidHash("hash-1")).thenReturn(key());
  }

  @Test
  void uploadsImageThenCreatesHtmlWrapperAndReturnsTrustedArtifact() {
    server.expect(requestTo("https://miniapp.example/api/open-api/media/images"))
        .andExpect(header("X-Open-Api-Openid", "openid-1"))
        .andExpect(header("X-CM-Trace-Id", "cmtrace_artifact123"))
        .andRespond(withSuccess("{\"code\":200,\"data\":{\"imageId\":\"img-1\",\"url\":\"https://cdn.example/img.png\"}}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://miniapp.example/api/open-api/html-content"))
        .andExpect(header("X-CM-Trace-Id", "cmtrace_artifact123"))
        .andRespond(withSuccess("{\"code\":200,\"data\":{\"contentKey\":\"content-1\",\"viewUrl\":\"https://miniapp.example/view\",\"miniappPath\":\"/pages/html-viewer/index?contentKey=content-1\",\"miniappScheme\":\"weixin://x\"}}", MediaType.APPLICATION_JSON));

    Map<String, Object> result = service.publishImage(
        "instance-1", "wechat-1", "mbreq-1", "cmtrace_artifact123", "周报", "说明",
        new MockMultipartFile("image", "report.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47})
    );

    assertThat(result.get("artifact")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("id", "artifact_content-1")
        .containsEntry("type", "image_report")
        .containsEntry("imageId", "img-1")
        .containsEntry("miniappPath", "/pages/html-viewer/index?contentKey=content-1");
    server.verify();
  }

  @Test
  void forwardsTraceIdWhenPublishingHtml() {
    server.expect(requestTo("https://miniapp.example/api/open-api/html-content"))
        .andExpect(header("X-CM-Trace-Id", "cmtrace_html123"))
        .andRespond(withSuccess("{\"code\":200,\"data\":{\"contentKey\":\"content-2\",\"viewUrl\":\"https://miniapp.example/view\",\"miniappPath\":\"/pages/html-viewer/index?contentKey=content-2\"}}", MediaType.APPLICATION_JSON));

    service.publishHtml(new MiniappArtifactHtmlRequest(
        "instance-1", "wechat-1", "mbreq-2", "cmtrace_html123", "报告", "", "<p>原文</p>"));

    server.verify();
  }

  private MiniappUserBindingEntity binding() {
    MiniappUserBindingEntity binding = new MiniappUserBindingEntity();
    binding.setOpenidHash("hash-1"); binding.setOpenid("openid-1"); binding.setInstanceId("instance-1");
    binding.setWechatUserId("wechat-1"); binding.setBindStatus("connected"); return binding;
  }

  private MiniappUserKeyEntity key() {
    MiniappUserKeyEntity key = new MiniappUserKeyEntity();
    key.setOpenidHash("hash-1"); key.setOpenid("openid-1"); key.setUserKey("cm_user_secret"); key.setEnabled(true); return key;
  }
}
