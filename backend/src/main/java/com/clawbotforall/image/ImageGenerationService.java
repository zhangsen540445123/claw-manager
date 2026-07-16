package com.clawbotforall.image;

import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.trace.IntegrationTraceEventRequest;
import com.clawbotforall.trace.IntegrationTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ImageGenerationService {
  private static final long MAX_BYTES = 10L * 1024 * 1024;
  private final ImageGenerationSettingsProvider settingsProvider;
  private final InstanceFileService files;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final IntegrationTraceService traces;

  public ImageGenerationService(ImageGenerationSettingsProvider settingsProvider, InstanceFileService files,
      ObjectMapper objectMapper, IntegrationTraceService traces) {
    this.settingsProvider = settingsProvider;
    this.files = files;
    this.objectMapper = objectMapper;
    this.traces = traces;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public Map<String, Object> generate(String instanceId, String prompt, String size, String quality, String traceId, String requestId) {
    ImageGenerationSettings settings = settingsProvider.current();
    if (!settings.configured() || settings.baseUrl().isBlank()) {
      event(traceId, instanceId, requestId, "image.config.validated", "failed", null, "IMAGE_CONFIG_INVALID", Map.of());
      throw new ApiException(HttpStatus.CONFLICT, "图片生成尚未配置或未启用。");
    }
    if (prompt == null || prompt.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "图片提示词不能为空。");
    long started = System.nanoTime();
    event(traceId, instanceId, requestId, "image.config.validated", "completed", null, null, Map.of("modelId", settings.modelId()));
    event(traceId, instanceId, requestId, "bridge.image_generate.started", "started", null, null, Map.of());
    try {
      Map<String, Object> requestBody = new LinkedHashMap<>();
      requestBody.put("model", settings.modelId());
      requestBody.put("prompt", prompt);
      requestBody.put("n", 1);
      requestBody.put("response_format", "b64_json");
      if (size != null && !size.isBlank()) requestBody.put("size", size.trim());
      if (quality != null && !quality.isBlank()) requestBody.put("quality", quality.trim());
      HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint(settings.baseUrl())))
          .timeout(Duration.ofMillis(settings.timeoutMs()))
          .header("Authorization", "Bearer " + settings.apiKey())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
          .build();
      event(traceId, instanceId, requestId, "image.provider.request.started", "started", null, null, Map.of("modelId", settings.modelId()));
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        event(traceId, instanceId, requestId, "image.provider.request.failed", "failed", response.statusCode(), "HTTP_" + response.statusCode(), Map.of("modelId", settings.modelId()));
        throw new ApiException(HttpStatus.BAD_GATEWAY, "图片生成服务返回 HTTP " + response.statusCode() + "。");
      }
      event(traceId, instanceId, requestId, "image.provider.request.completed", "completed", response.statusCode(), null, Map.of("modelId", settings.modelId()));
      JsonNode item = objectMapper.readTree(response.body()).path("data").path(0);
      byte[] bytes;
      if (item.hasNonNull("b64_json")) {
        try { bytes = Base64.getDecoder().decode(item.get("b64_json").asText()); }
        catch (IllegalArgumentException error) {
          event(traceId, instanceId, requestId, "image.response.decoded", "failed", null, "INVALID_BASE64", Map.of());
          throw new ApiException(HttpStatus.BAD_GATEWAY, "图片生成服务返回了非法 Base64。");
        }
      } else if (item.hasNonNull("url")) {
        bytes = downloadImage(item.get("url").asText());
      } else {
        event(traceId, instanceId, requestId, "image.response.decoded", "failed", null, "IMAGE_RESULT_MISSING", Map.of());
        throw new ApiException(HttpStatus.BAD_GATEWAY, "图片生成服务未返回图片。");
      }
      ImageInfo info;
      try {
        info = inspect(bytes);
      } catch (ApiException error) {
        event(traceId, instanceId, requestId, "image.response.decoded", "failed", error.getStatus().value(), "IMAGE_DECODE_FAILED", Map.of());
        throw error;
      }
      event(traceId, instanceId, requestId, "image.response.decoded", "completed", null, null, Map.of("mime", info.mime(), "width", info.width(), "height", info.height(), "fileSize", bytes.length));
      InstancePaths paths = files.paths(instanceId);
      Path directory = paths.workspaceDir().resolve("media").resolve("generated");
      try {
        Files.createDirectories(directory);
        String id = "img_" + UUID.randomUUID().toString().replace("-", "");
        Path temporary = Files.createTempFile(directory, "." + id + "-", ".tmp");
        Path target = directory.resolve(id + info.extension());
        try {
          Files.write(temporary, bytes);
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
          Files.deleteIfExists(temporary);
        }
        event(traceId, instanceId, requestId, "image.file.written", "completed", null, null, Map.of("imageId", id, "mime", info.mime(), "width", info.width(), "height", info.height(), "fileSize", bytes.length));
        event(traceId, instanceId, requestId, "image.generation.completed", "completed", null, null, Map.of("imageId", id));
        event(traceId, instanceId, requestId, "bridge.image_generate.completed", "completed", null, null, Map.of("imageId", id));
        return Map.of("imageId", id, "localPath", "/workspace/media/generated/" + target.getFileName(),
            "mime", info.mime(), "width", info.width(), "height", info.height(), "fileSize", bytes.length,
            "elapsedMs", (System.nanoTime() - started) / 1_000_000);
      } catch (IOException error) {
        event(traceId, instanceId, requestId, "image.file.written", "failed", null, "IMAGE_FILE_WRITE_FAILED", Map.of());
        throw error;
      }
    } catch (ApiException error) {
      event(traceId, instanceId, requestId, "bridge.image_generate.failed", "failed", error.getStatus().value(), "IMAGE_GENERATION_FAILED", Map.of());
      throw error;
    } catch (IOException | InterruptedException error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      event(traceId, instanceId, requestId, "image.generation.failed", "failed", null, error.getClass().getSimpleName(), Map.of());
      throw new ApiException(HttpStatus.BAD_GATEWAY, "图片生成服务调用失败。");
    }
  }

  private byte[] downloadImage(String value) throws IOException, InterruptedException {
    URI uri = URI.create(value);
    if (!"https".equalsIgnoreCase(uri.getScheme())) throw new ApiException(HttpStatus.BAD_GATEWAY, "图片 URL 必须使用 HTTPS。");
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ApiException(HttpStatus.BAD_GATEWAY, "图片下载失败。");
    if (response.body().length > MAX_BYTES) throw new ApiException(HttpStatus.BAD_GATEWAY, "生成图片超过 10 MB 限制。");
    return response.body();
  }

  private ImageInfo inspect(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new ApiException(HttpStatus.BAD_GATEWAY, "生成图片大小无效。");
    String mime = detectMime(bytes);
    if (mime == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "生成内容不是受支持的图片格式。");
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) throw new IOException();
      return new ImageInfo(mime, image.getWidth(), image.getHeight(), switch (mime) {
        case "image/png" -> ".png"; case "image/jpeg" -> ".jpg"; default -> ".webp";
      });
    } catch (IOException error) { throw new ApiException(HttpStatus.BAD_GATEWAY, "无法解析生成图片尺寸。"); }
  }

  private String detectMime(byte[] bytes) {
    if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) return "image/png";
    if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "image/jpeg";
    if (bytes.length >= 12 && new String(bytes, 0, 4).equals("RIFF") && new String(bytes, 8, 4).equals("WEBP")) return "image/webp";
    return null;
  }

  private String endpoint(String baseUrl) { return baseUrl.replaceAll("/+$", "") + "/images/generations"; }
  private void event(String traceId, String instanceId, String requestId, String stage, String status, Integer httpStatus, String errorCode, Map<String,Object> details) {
    if (traceId == null || traceId.isBlank()) return;
    try { traces.record(new IntegrationTraceEventRequest(traceId, requestId, "claw-manager", stage, status, "internal", instanceId, "", "", "image_generate", requestId, httpStatus, null, null, errorCode, "", details), traceId); }
    catch (RuntimeException ignored) { }
  }
  private record ImageInfo(String mime, int width, int height, String extension) {}
}
