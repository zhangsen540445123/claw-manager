package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.clawbotforall.trace.IntegrationTraceService;
import com.clawbotforall.trace.IntegrationTraceEventRequest;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
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
  @Mock InstanceFileService instanceFiles;
  @TempDir Path tempDir;
  private MockRestServiceServer server;
  private MiniappArtifactService service;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    service = new MiniappArtifactService(bindingMapper, keyMapper, builder, "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces);
    lenient().when(bindingMapper.findByWechatUserId("wechat-1")).thenReturn(binding());
    lenient().when(keyMapper.findByOpenidHash("hash-1")).thenReturn(key());
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

  @Test
  void publishesGeneratedImageByServerSideImageId() throws Exception {
    String generatedImageId = "img_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    Path workspace = tempDir.resolve("workspace");
    Path generated = workspace.resolve("media/generated");
    Files.createDirectories(generated);
    Files.write(generated.resolve(generatedImageId + ".png"), java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
    Assumptions.assumeTrue(supportsSecureDirectoryStream(workspace), "当前文件系统不支持安全目录句柄");
    when(instanceFiles.paths("instance-1")).thenReturn(new InstancePaths(tempDir, tempDir.resolve("home"), workspace, tempDir.resolve("logs")));
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    service = new MiniappArtifactService(bindingMapper, keyMapper, builder, "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces, instanceFiles);
    server.expect(requestTo("https://miniapp.example/api/open-api/media/images"))
        .andRespond(withSuccess("{\"code\":200,\"data\":{\"imageId\":\"uploaded-1\",\"url\":\"https://cdn.example/generated.png\"}}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://miniapp.example/api/open-api/html-content"))
        .andRespond(withSuccess("{\"code\":200,\"data\":{\"contentKey\":\"content-generated\",\"miniappPath\":\"/pages/html-viewer/index?contentKey=content-generated\"}}", MediaType.APPLICATION_JSON));

    Map<String, Object> result = service.publishGeneratedImage(new MiniappGeneratedArtifactRequest(
        "instance-1", "wechat-1", "mbreq-generated", "cmtrace_generated123", generatedImageId, "海报", "说明"));

    assertThat(result.get("artifact")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("imageId", "uploaded-1")
        .containsEntry("contentKey", "content-generated");
    server.verify();
  }

  @Test
  void reportsGeneratedImageLookupFailureWithSafeErrorCode() {
    String generatedImageId = "img_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    Path workspace = tempDir.resolve("missing-workspace");
    when(instanceFiles.paths("instance-1")).thenReturn(new InstancePaths(tempDir, tempDir.resolve("home"), workspace, tempDir.resolve("logs")));
    service = new MiniappArtifactService(bindingMapper, keyMapper, RestClient.builder(), "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces, instanceFiles);

    assertThatThrownBy(() -> service.publishGeneratedImage(new MiniappGeneratedArtifactRequest(
        "instance-1", "wechat-1", "mbreq-missing", "cmtrace_missing123", generatedImageId, "海报", "")))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("生成图片不存在");

    ArgumentCaptor<IntegrationTraceEventRequest> captor = ArgumentCaptor.forClass(IntegrationTraceEventRequest.class);
    verify(traces, atLeastOnce()).record(captor.capture(), org.mockito.ArgumentMatchers.eq("cmtrace_missing123"));
    assertThat(captor.getAllValues()).anySatisfy(event -> {
      assertThat(event.stage()).isEqualTo("bridge.publish_image.failed");
      assertThat(event.errorCode()).isEqualTo("GENERATED_IMAGE_NOT_FOUND");
      assertThat(event.errorMessage()).contains("生成图片不存在").doesNotContain(workspace.toString());
    });
  }

  @Test
  void rejectsInvalidGeneratedImageId() {
    service = new MiniappArtifactService(bindingMapper, keyMapper, RestClient.builder(), "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces, instanceFiles);

    assertThatThrownBy(() -> service.publishGeneratedImage(new MiniappGeneratedArtifactRequest(
        "instance-1", "wechat-1", "mbreq-invalid", "cmtrace_invalid123", "../secret", "", "")))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("格式不正确");
  }

  @Test
  void rejectsGeneratedImageFromAnotherInstance() {
    service = new MiniappArtifactService(bindingMapper, keyMapper, RestClient.builder(), "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces, instanceFiles);

    assertThatThrownBy(() -> service.publishGeneratedImage(new MiniappGeneratedArtifactRequest(
        "instance-2", "wechat-1", "mbreq-cross", "cmtrace_cross123", "img_cccccccccccccccccccccccccccccccc", "", "")))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("不属于当前 OpenClaw 实例");
  }

  @Test
  void rejectsOversizedGeneratedImage() throws Exception {
    String generatedImageId = "img_dddddddddddddddddddddddddddddddd";
    Path workspace = tempDir.resolve("large-workspace");
    Path generated = workspace.resolve("media/generated");
    Files.createDirectories(generated);
    Files.write(generated.resolve(generatedImageId + ".png"), new byte[10 * 1024 * 1024 + 1]);
    Assumptions.assumeTrue(supportsSecureDirectoryStream(workspace), "当前文件系统不支持安全目录句柄");
    when(instanceFiles.paths("instance-1")).thenReturn(new InstancePaths(tempDir, tempDir.resolve("home"), workspace, tempDir.resolve("logs")));
    service = new MiniappArtifactService(bindingMapper, keyMapper, RestClient.builder(), "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces, instanceFiles);

    assertThatThrownBy(() -> service.publishGeneratedImage(new MiniappGeneratedArtifactRequest(
        "instance-1", "wechat-1", "mbreq-large", "cmtrace_large123", generatedImageId, "", "")))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("大小无效");
  }

  @Test
  void rejectsGeneratedImageSymbolicLink() throws Exception {
    String generatedImageId = "img_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    Path workspace = tempDir.resolve("link-workspace");
    Path generated = workspace.resolve("media/generated");
    Files.createDirectories(generated);
    Path outside = tempDir.resolve("outside.png");
    Files.write(outside, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
    try {
      Files.createSymbolicLink(generated.resolve(generatedImageId + ".png"), outside);
    } catch (UnsupportedOperationException | java.io.IOException | SecurityException error) {
      Assumptions.assumeTrue(false, "当前文件系统不支持创建符号链接");
    }
    when(instanceFiles.paths("instance-1")).thenReturn(new InstancePaths(tempDir, tempDir.resolve("home"), workspace, tempDir.resolve("logs")));
    service = new MiniappArtifactService(bindingMapper, keyMapper, RestClient.builder(), "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces, instanceFiles);

    assertThatThrownBy(() -> service.publishGeneratedImage(new MiniappGeneratedArtifactRequest(
        "instance-1", "wechat-1", "mbreq-link", "cmtrace_link123", generatedImageId, "", "")))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("路径无效");
  }

  @Test
  void rejectsGeneratedImageDirectorySymbolicLink() throws Exception {
    String generatedImageId = "img_ffffffffffffffffffffffffffffffff";
    Path workspace = tempDir.resolve("directory-link-workspace");
    Path outsideMedia = tempDir.resolve("outside-media");
    Path outsideGenerated = outsideMedia.resolve("generated");
    Files.createDirectories(workspace);
    Files.createDirectories(outsideGenerated);
    Files.write(outsideGenerated.resolve(generatedImageId + ".png"), java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
    try {
      Files.createSymbolicLink(workspace.resolve("media"), outsideMedia);
    } catch (UnsupportedOperationException | java.io.IOException | SecurityException error) {
      Assumptions.assumeTrue(false, "当前文件系统不支持创建符号链接");
    }
    when(instanceFiles.paths("instance-1")).thenReturn(new InstancePaths(tempDir, tempDir.resolve("home"), workspace, tempDir.resolve("logs")));
    service = new MiniappArtifactService(bindingMapper, keyMapper, RestClient.builder(), "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces, instanceFiles);

    assertThatThrownBy(() -> service.publishGeneratedImage(new MiniappGeneratedArtifactRequest(
        "instance-1", "wechat-1", "mbreq-directory-link", "cmtrace_directorylink123", generatedImageId, "", "")))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("路径无效");
  }

  @Test
  void failsClosedWhenSecureDirectoryStreamsAreUnavailable() throws Exception {
    String generatedImageId = "img_11111111111111111111111111111111";
    Path workspace = tempDir.resolve("no-secure-stream-workspace");
    Path generated = workspace.resolve("media/generated");
    Files.createDirectories(generated);
    Files.write(generated.resolve(generatedImageId + ".png"), java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
    Assumptions.assumeFalse(supportsSecureDirectoryStream(workspace), "当前文件系统支持安全目录句柄");
    when(instanceFiles.paths("instance-1")).thenReturn(new InstancePaths(tempDir, tempDir.resolve("home"), workspace, tempDir.resolve("logs")));
    service = new MiniappArtifactService(bindingMapper, keyMapper, RestClient.builder(), "https://miniapp.example/api",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), traces, instanceFiles);

    assertThatThrownBy(() -> service.publishGeneratedImage(new MiniappGeneratedArtifactRequest(
        "instance-1", "wechat-1", "mbreq-no-secure", "cmtrace_nosecure123", generatedImageId, "", "")))
        .isInstanceOf(com.clawbotforall.web.ApiException.class)
        .hasMessageContaining("不支持安全读取");
  }

  private boolean supportsSecureDirectoryStream(Path directory) throws Exception {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      return stream instanceof SecureDirectoryStream<?>;
    }
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
