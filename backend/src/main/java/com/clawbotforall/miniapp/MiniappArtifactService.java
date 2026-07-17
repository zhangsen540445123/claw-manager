package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.trace.IntegrationTraceEventRequest;
import com.clawbotforall.trace.IntegrationTraceService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MiniappArtifactService {
  private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
  private static final String[] IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp"};
  private final MiniappUserBindingMapper bindingMapper;
  private final MiniappUserKeyMapper keyMapper;
  private final RestClient restClient;
  private final String baseUrl;
  private final Clock clock;
  private final IntegrationTraceService traces;
  private final InstanceFileService instanceFiles;

  @Autowired
  public MiniappArtifactService(MiniappUserBindingMapper bindingMapper, MiniappUserKeyMapper keyMapper,
      RestClient.Builder builder, @Value("${clawbot.miniapp-open-api-base-url:}") String baseUrl,
      IntegrationTraceService traces, InstanceFileService instanceFiles) {
    this(bindingMapper, keyMapper, builder, baseUrl, Clock.systemUTC(), traces, instanceFiles);
  }

  MiniappArtifactService(MiniappUserBindingMapper bindingMapper, MiniappUserKeyMapper keyMapper,
      RestClient.Builder builder, String baseUrl, Clock clock) {
    this(bindingMapper, keyMapper, builder, baseUrl, clock, null, null);
  }

  MiniappArtifactService(MiniappUserBindingMapper bindingMapper, MiniappUserKeyMapper keyMapper,
      RestClient.Builder builder, String baseUrl, Clock clock, IntegrationTraceService traces) {
    this(bindingMapper, keyMapper, builder, baseUrl, clock, traces, null);
  }

  MiniappArtifactService(MiniappUserBindingMapper bindingMapper, MiniappUserKeyMapper keyMapper,
      RestClient.Builder builder, String baseUrl, Clock clock, IntegrationTraceService traces, InstanceFileService instanceFiles) {
    this.bindingMapper = bindingMapper;
    this.keyMapper = keyMapper;
    this.restClient = builder.build();
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceFirst("/+$", "");
    this.clock = clock;
    this.traces = traces;
    this.instanceFiles = instanceFiles;
  }

  public Map<String, Object> publishHtml(MiniappArtifactHtmlRequest request) {
    event(request.cmTraceId(), request.instanceId(), request.requestId(), "artifact.html.create.started", "started", null, Map.of());
    try {
      Identity identity = identity(request.instanceId(), request.requesterSenderId());
      event(request.cmTraceId(), request.instanceId(), request.requestId(), "artifact.identity.resolved", "completed", null, Map.of());
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("htmlContent", required(request.htmlContent(), "HTML 内容不能为空。"));
      if (!blank(request.title())) payload.put("title", request.title().trim());
      if (!blank(request.contentKey())) payload.put("contentKey", request.contentKey().trim());
      Map<String, Object> data = postJson("/open-api/html-content", payload, identity, request.requestId(), request.cmTraceId());
      event(request.cmTraceId(), request.instanceId(), request.requestId(), "artifact.html.create.completed", "completed", null, Map.of("contentKey", data.getOrDefault("contentKey", "")));
      keyMapper.updateLastUsed(identity.binding().getOpenidHash(), clock.instant().toString());
      return Map.of("artifact", artifact("html_report", request.title(), data, Map.of()));
    } catch (RuntimeException error) {
      event(request.cmTraceId(), request.instanceId(), request.requestId(), "artifact.html.create.failed", "failed", status(error), Map.of());
      throw error;
    }
  }

  public Map<String, Object> publishImage(String instanceId, String requesterSenderId, String requestId,
      String title, String description, MultipartFile image) {
    return publishImage(instanceId, requesterSenderId, requestId, "", title, description, image);
  }

  public Map<String, Object> publishImage(String instanceId, String requesterSenderId, String requestId, String cmTraceId,
      String title, String description, MultipartFile image) {
    return publishImageContent(instanceId, requesterSenderId, requestId, cmTraceId, title, description, () -> {
      if (image == null || image.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "图片不能为空。");
      return new ImageContent(image.getBytes(), image.getOriginalFilename() == null ? "artifact.png" : image.getOriginalFilename(),
          image.getContentType() == null ? "application/octet-stream" : image.getContentType());
    });
  }

  public Map<String, Object> publishGeneratedImage(MiniappGeneratedArtifactRequest request) {
    if (request == null) throw new ApiException(HttpStatus.BAD_REQUEST, "生成图片请求不能为空。");
    return publishImageContent(request.instanceId(), request.requesterSenderId(), request.requestId(), request.cmTraceId(),
        request.title(), request.description(), () -> generatedImage(request.instanceId(), request.generatedImageId()));
  }

  private Map<String, Object> publishImageContent(String instanceId, String requesterSenderId, String requestId, String cmTraceId,
      String title, String description, ImageLoader loader) {
    event(cmTraceId, instanceId, requestId, "bridge.publish_image.started", "started", null, Map.of());
    String phase = "identity";
    try {
      Identity identity = identity(instanceId, requesterSenderId);
      event(cmTraceId, instanceId, requestId, "artifact.identity.resolved", "completed", null, Map.of());
      ImageContent image = loader.load();
      phase = "upload";
      event(cmTraceId, instanceId, requestId, "artifact.image.upload.started", "started", null, Map.of());
      MultipartBodyBuilder multipart = new MultipartBodyBuilder();
      byte[] bytes = image.bytes();
      multipart.part("image", new ByteArrayResource(bytes) {
        @Override public String getFilename() { return image.filename(); }
      }).contentType(MediaType.parseMediaType(image.mime()));
      if (!blank(title)) multipart.part("title", title.trim());
      ResponseEntity<Object> response = restClient.post().uri(baseUrl + "/open-api/media/images")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .header("X-Open-Api-Openid", identity.key().getOpenid())
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + identity.key().getUserKey())
          .header("X-CM-Bridge-Request-Id", safe(requestId))
          .header("X-CM-Trace-Id", safeTrace(cmTraceId))
          .body(multipart.build()).retrieve().toEntity(Object.class);
      Map<String, Object> imageData = businessData(response.getBody());
      event(cmTraceId, instanceId, requestId, "artifact.image.upload.completed", "completed", response.getStatusCode().value(), Map.of("imageId", imageData.getOrDefault("imageId", "")));
      String imageUrl = String.valueOf(imageData.getOrDefault("url", ""));
      if (imageUrl.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "图片接口未返回访问地址。");
      phase = "html";
      event(cmTraceId, instanceId, requestId, "artifact.html.create.started", "started", null, Map.of());
      String html = imageHtml(title, description, imageUrl);
      Map<String, Object> htmlData = postJson("/open-api/html-content", Map.of(
          "title", blank(title) ? "AI 生成图片" : title.trim(), "htmlContent", html), identity, requestId, cmTraceId);
      event(cmTraceId, instanceId, requestId, "artifact.html.create.completed", "completed", null, Map.of("contentKey", htmlData.getOrDefault("contentKey", "")));
      event(cmTraceId, instanceId, requestId, "bridge.publish_image.completed", "completed", null, Map.of("imageId", imageData.getOrDefault("imageId", ""), "contentKey", htmlData.getOrDefault("contentKey", "")));
      keyMapper.updateLastUsed(identity.binding().getOpenidHash(), clock.instant().toString());
      return Map.of("artifact", artifact("image_report", title, htmlData, Map.of(
          "imageId", imageData.getOrDefault("imageId", ""), "imageUrl", imageUrl)));
    } catch (RuntimeException error) {
      String errorCode = errorCode(error, phase);
      if ("upload".equals(phase)) event(cmTraceId, instanceId, requestId, "artifact.image.upload.failed", "failed", status(error), errorCode, error.getMessage(), Map.of());
      if ("html".equals(phase)) event(cmTraceId, instanceId, requestId, "artifact.html.create.failed", "failed", status(error), errorCode, error.getMessage(), Map.of());
      event(cmTraceId, instanceId, requestId, "bridge.publish_image.failed", "failed", status(error), errorCode, error.getMessage(), Map.of());
      if (error instanceof ApiException apiError) throw apiError;
      throw new ApiException(HttpStatus.BAD_GATEWAY, "发布图片失败: " + error.getMessage());
    } catch (Exception error) {
      event(cmTraceId, instanceId, requestId, "artifact.image.upload.failed", "failed", null, "ARTIFACT_PUBLISH_FAILED", "发布图片失败。", Map.of());
      event(cmTraceId, instanceId, requestId, "bridge.publish_image.failed", "failed", null, "ARTIFACT_PUBLISH_FAILED", "发布图片失败。", Map.of());
      throw new ApiException(HttpStatus.BAD_GATEWAY, "发布图片失败: " + error.getMessage());
    }
  }

  private ImageContent generatedImage(String instanceId, String generatedImageId) {
    if (instanceFiles == null) throw generatedError(HttpStatus.CONFLICT, "GENERATED_IMAGE_SERVICE_UNAVAILABLE", "实例文件服务不可用。");
    String id = required(generatedImageId, "generatedImageId 不能为空。").trim();
    if (!id.matches("img_[a-f0-9]{32}")) throw generatedError(HttpStatus.BAD_REQUEST, "GENERATED_IMAGE_REFERENCE_INVALID", "generatedImageId 格式不正确。");
    InstancePaths paths = instanceFiles.paths(required(instanceId, "实例不能为空。").trim());
    if (paths == null || paths.workspaceDir() == null) {
      throw generatedError(HttpStatus.NOT_FOUND, "GENERATED_IMAGE_NOT_FOUND", "生成图片不存在。");
    }
    Path workspace = paths.workspaceDir().toAbsolutePath().normalize();
    try {
      validateGeneratedDirectories(workspace);
      LoadedImage image = loadGeneratedImage(workspace, id);
      String mime = detectImageMime(image.bytes());
      if (mime == null) throw generatedError(HttpStatus.BAD_REQUEST, "GENERATED_IMAGE_INVALID", "生成文件不是受支持的图片格式。");
      return new ImageContent(image.bytes(), image.filename(), mime);
    } catch (GeneratedImageException error) {
      throw error;
    } catch (NoSuchFileException error) {
      throw generatedError(HttpStatus.NOT_FOUND, "GENERATED_IMAGE_NOT_FOUND", "生成图片不存在。");
    } catch (IOException error) {
      throw generatedError(HttpStatus.FORBIDDEN, "GENERATED_IMAGE_INVALID", "生成图片路径无效。");
    }
  }

  private void validateGeneratedDirectories(Path workspace) throws IOException {
    Path media = workspace.resolve("media");
    Path generated = media.resolve("generated");
    for (Path directory : new Path[]{workspace, media, generated}) {
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(directory.toString());
      if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw generatedError(HttpStatus.FORBIDDEN, "GENERATED_IMAGE_INVALID", "生成图片路径无效。");
      }
    }
    Path realWorkspace = workspace.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (!media.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realWorkspace)
        || !generated.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realWorkspace)) {
      throw generatedError(HttpStatus.FORBIDDEN, "GENERATED_IMAGE_INVALID", "生成图片路径无效。");
    }
  }

  private LoadedImage loadGeneratedImage(Path workspace, String id) throws IOException {
    try (DirectoryStream<Path> workspaceStream = Files.newDirectoryStream(workspace)) {
      if (workspaceStream instanceof SecureDirectoryStream<Path> secureWorkspace) {
        try (SecureDirectoryStream<Path> media = secureWorkspace.newDirectoryStream(Path.of("media"), LinkOption.NOFOLLOW_LINKS);
             SecureDirectoryStream<Path> generated = media.newDirectoryStream(Path.of("generated"), LinkOption.NOFOLLOW_LINKS)) {
          return loadSecureImage(generated, id);
        }
      }
    }
    throw generatedError(HttpStatus.CONFLICT, "GENERATED_IMAGE_SECURE_READ_UNAVAILABLE", "当前文件系统不支持安全读取生成图片。");
  }

  private LoadedImage loadSecureImage(SecureDirectoryStream<Path> directory, String id) throws IOException {
    Path selected = null;
    BasicFileAttributes selectedAttributes = null;
    for (String extension : IMAGE_EXTENSIONS) {
      Path relative = Path.of(id + extension);
      BasicFileAttributeView view = directory.getFileAttributeView(relative, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      try {
        BasicFileAttributes attributes = view == null ? null : view.readAttributes();
        if (attributes == null) continue;
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) throw invalidGeneratedPath();
        if (selected != null) throw generatedError(HttpStatus.CONFLICT, "GENERATED_IMAGE_AMBIGUOUS", "生成图片引用不唯一。");
        selected = relative;
        selectedAttributes = attributes;
      } catch (NoSuchFileException ignored) {
        // Try the next supported extension.
      }
    }
    if (selected == null || selectedAttributes == null) throw new NoSuchFileException(id);
    validateImageSize(selectedAttributes.size());
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = directory.newByteChannel(selected, options)) {
      return new LoadedImage(readLimited(channel), selected.getFileName().toString());
    }
  }

  private byte[] readLimited(SeekableByteChannel channel) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(8192);
    long total = 0;
    while (channel.read(buffer) >= 0) {
      buffer.flip();
      int count = buffer.remaining();
      total += count;
      if (total > MAX_IMAGE_BYTES) throw generatedError(HttpStatus.BAD_REQUEST, "GENERATED_IMAGE_INVALID", "生成图片大小无效。");
      output.write(buffer.array(), buffer.position(), count);
      buffer.clear();
    }
    validateImageSize(total);
    return output.toByteArray();
  }

  private void validateImageSize(long size) {
    if (size <= 0 || size > MAX_IMAGE_BYTES) throw generatedError(HttpStatus.BAD_REQUEST, "GENERATED_IMAGE_INVALID", "生成图片大小无效。");
  }

  private GeneratedImageException invalidGeneratedPath() {
    return generatedError(HttpStatus.FORBIDDEN, "GENERATED_IMAGE_INVALID", "生成图片路径无效。");
  }

  private String detectImageMime(byte[] bytes) {
    if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) return "image/png";
    if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "image/jpeg";
    if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
        && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
    return null;
  }

  private Map<String, Object> postJson(String path, Map<String, Object> payload, Identity identity, String requestId, String cmTraceId) {
    ResponseEntity<Object> response = restClient.post().uri(baseUrl + path)
        .header("X-Open-Api-Openid", identity.key().getOpenid())
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + identity.key().getUserKey())
        .header("X-CM-Bridge-Request-Id", safe(requestId))
        .header("X-CM-Trace-Id", safeTrace(cmTraceId))
        .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toEntity(Object.class);
    return businessData(response.getBody());
  }

  private Map<String, Object> businessData(Object body) {
    if (!(body instanceof Map<?, ?> map)) throw new ApiException(HttpStatus.BAD_GATEWAY, "小程序接口响应格式不正确。");
    Object rawCode = map.containsKey("code") ? map.get("code") : 200;
    int code = Integer.parseInt(String.valueOf(rawCode));
    Object rawMessage = map.containsKey("message") ? map.get("message") : "";
    if (code != 200) throw new ApiException(HttpStatus.BAD_GATEWAY, "小程序业务接口失败(" + code + "): " + rawMessage);
    Object data = map.get("data");
    if (!(data instanceof Map<?, ?> values)) return Map.of();
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    values.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private Identity identity(String instanceId, String sender) {
    if (baseUrl.isBlank()) throw new ApiException(HttpStatus.CONFLICT, "小程序 Open API 地址尚未配置。");
    if (blank(instanceId) || blank(sender)) throw new ApiException(HttpStatus.BAD_REQUEST, "缺少实例或发送者身份。");
    MiniappUserBindingEntity binding = sender.startsWith("miniapp:")
        ? bindingMapper.findByOpenidHash(sender.substring("miniapp:".length())) : bindingMapper.findByWechatUserId(sender);
    if (binding == null || !"connected".equals(binding.getBindStatus())) throw new ApiException(HttpStatus.CONFLICT, "当前用户尚未完成小程序微信绑定。");
    if (!instanceId.equals(binding.getInstanceId())) throw new ApiException(HttpStatus.FORBIDDEN, "发送者不属于当前 OpenClaw 实例。");
    MiniappUserKeyEntity key = keyMapper.findByOpenidHash(binding.getOpenidHash());
    if (key == null || !key.isEnabled() || blank(key.getUserKey())) throw new ApiException(HttpStatus.UNAUTHORIZED, "当前用户没有可用的小程序用户 Key。");
    return new Identity(binding, key);
  }

  private Map<String, Object> artifact(String type, String title, Map<String, Object> navigation, Map<String, Object> media) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    String contentKey = String.valueOf(navigation.getOrDefault("contentKey", ""));
    result.put("id", "artifact_" + contentKey);
    result.put("type", type);
    result.put("title", blank(title) ? ("image_report".equals(type) ? "AI 生成图片" : "AI 生成内容") : title.trim());
    for (String key : new String[]{"contentKey", "viewUrl", "miniappPath", "miniappScheme"}) result.put(key, navigation.getOrDefault(key, ""));
    result.putAll(media);
    return result;
  }

  private String imageHtml(String title, String description, String imageUrl) {
    return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>" + escape(title) + "</title><style>body{margin:0;background:#f5f6f8;color:#1f2937;font-family:sans-serif}.wrap{max-width:960px;margin:auto;padding:16px}img{display:block;width:100%;height:auto;background:#fff}h1{font-size:20px}p{line-height:1.6}</style></head>"
        + "<body><main class=\"wrap\"><h1>" + escape(blank(title) ? "AI 生成图片" : title) + "</h1><img src=\"" + escape(imageUrl) + "\" alt=\"生成图片\">"
        + (blank(description) ? "" : "<p>" + escape(description) + "</p>") + "</main></body></html>";
  }

  private static String escape(String value) { return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
  private static String required(String value, String message) { if (blank(value)) throw new ApiException(HttpStatus.BAD_REQUEST, message); return value; }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static String safe(String value) { return value == null || value.isBlank() ? "mbreq_unknown" : value.substring(0, Math.min(100, value.length())); }
  private static String safeTrace(String value) { return value == null || value.isBlank() ? "cmtrace_unknown" : value.substring(0, Math.min(96, value.length())); }
  private static Integer status(RuntimeException error) { return error instanceof ApiException apiError ? apiError.getStatus().value() : null; }
  private static String errorCode(RuntimeException error, String phase) {
    if (error instanceof GeneratedImageException generated) return generated.code();
    if ("upload".equals(phase)) return "ARTIFACT_UPLOAD_FAILED";
    if ("html".equals(phase)) return "ARTIFACT_HTML_FAILED";
    return "ARTIFACT_TOOL_FAILED";
  }
  private static GeneratedImageException generatedError(HttpStatus status, String code, String message) {
    return new GeneratedImageException(status, code, message);
  }
  private void event(String traceId, String instanceId, String requestId, String stage, String status, Integer httpStatus, Map<String,Object> details) {
    event(traceId, instanceId, requestId, stage, status, httpStatus, "", "", details);
  }
  private void event(String traceId, String instanceId, String requestId, String stage, String status, Integer httpStatus,
      String errorCode, String errorMessage, Map<String,Object> details) {
    if (traces == null || traceId == null || traceId.isBlank()) return;
    try { traces.record(new IntegrationTraceEventRequest(traceId, requestId, "claw-manager", stage, status, "internal", instanceId, "", "", "miniapp_artifact", requestId, httpStatus, null, null, errorCode, errorMessage, details), traceId); } catch (RuntimeException ignored) { }
  }
  private record Identity(MiniappUserBindingEntity binding, MiniappUserKeyEntity key) {}
  private record ImageContent(byte[] bytes, String filename, String mime) {}
  private record LoadedImage(byte[] bytes, String filename) {}
  private static final class GeneratedImageException extends ApiException {
    private final String code;
    private GeneratedImageException(HttpStatus status, String code, String message) { super(status, message); this.code = code; }
    private String code() { return code; }
  }
  @FunctionalInterface private interface ImageLoader { ImageContent load() throws Exception; }
}
