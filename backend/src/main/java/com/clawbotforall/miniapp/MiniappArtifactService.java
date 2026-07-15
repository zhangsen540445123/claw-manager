package com.clawbotforall.miniapp;

import com.clawbotforall.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
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
  private final MiniappUserBindingMapper bindingMapper;
  private final MiniappUserKeyMapper keyMapper;
  private final RestClient restClient;
  private final String baseUrl;
  private final Clock clock;

  @Autowired
  public MiniappArtifactService(MiniappUserBindingMapper bindingMapper, MiniappUserKeyMapper keyMapper,
      RestClient.Builder builder, @Value("${clawbot.miniapp-open-api-base-url:}") String baseUrl) {
    this(bindingMapper, keyMapper, builder, baseUrl, Clock.systemUTC());
  }

  MiniappArtifactService(MiniappUserBindingMapper bindingMapper, MiniappUserKeyMapper keyMapper,
      RestClient.Builder builder, String baseUrl, Clock clock) {
    this.bindingMapper = bindingMapper;
    this.keyMapper = keyMapper;
    this.restClient = builder.build();
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceFirst("/+$", "");
    this.clock = clock;
  }

  public Map<String, Object> publishHtml(MiniappArtifactHtmlRequest request) {
    Identity identity = identity(request.instanceId(), request.requesterSenderId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("htmlContent", required(request.htmlContent(), "HTML 内容不能为空。"));
    if (!blank(request.title())) payload.put("title", request.title().trim());
    if (!blank(request.contentKey())) payload.put("contentKey", request.contentKey().trim());
    Map<String, Object> data = postJson("/open-api/html-content", payload, identity, request.requestId());
    keyMapper.updateLastUsed(identity.binding().getOpenidHash(), clock.instant().toString());
    return Map.of("artifact", artifact("html_report", request.title(), data, Map.of()));
  }

  public Map<String, Object> publishImage(String instanceId, String requesterSenderId, String requestId,
      String title, String description, MultipartFile image) {
    Identity identity = identity(instanceId, requesterSenderId);
    if (image == null || image.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "图片不能为空。");
    try {
      MultipartBodyBuilder multipart = new MultipartBodyBuilder();
      byte[] bytes = image.getBytes();
      multipart.part("image", new ByteArrayResource(bytes) {
        @Override public String getFilename() { return image.getOriginalFilename() == null ? "artifact.png" : image.getOriginalFilename(); }
      }).contentType(MediaType.parseMediaType(image.getContentType() == null ? "application/octet-stream" : image.getContentType()));
      if (!blank(title)) multipart.part("title", title.trim());
      ResponseEntity<Object> response = restClient.post().uri(baseUrl + "/open-api/media/images")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .header("X-Open-Api-Openid", identity.key().getOpenid())
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + identity.key().getUserKey())
          .header("X-CM-Bridge-Request-Id", safe(requestId))
          .body(multipart.build()).retrieve().toEntity(Object.class);
      Map<String, Object> imageData = businessData(response.getBody());
      String imageUrl = String.valueOf(imageData.getOrDefault("url", ""));
      if (imageUrl.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "图片接口未返回访问地址。");
      String html = imageHtml(title, description, imageUrl);
      Map<String, Object> htmlData = postJson("/open-api/html-content", Map.of(
          "title", blank(title) ? "AI 生成图片" : title.trim(), "htmlContent", html), identity, requestId);
      keyMapper.updateLastUsed(identity.binding().getOpenidHash(), clock.instant().toString());
      return Map.of("artifact", artifact("image_report", title, htmlData, Map.of(
          "imageId", imageData.getOrDefault("imageId", ""), "imageUrl", imageUrl)));
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "发布图片失败: " + error.getMessage());
    }
  }

  private Map<String, Object> postJson(String path, Map<String, Object> payload, Identity identity, String requestId) {
    ResponseEntity<Object> response = restClient.post().uri(baseUrl + path)
        .header("X-Open-Api-Openid", identity.key().getOpenid())
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + identity.key().getUserKey())
        .header("X-CM-Bridge-Request-Id", safe(requestId))
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
  private record Identity(MiniappUserBindingEntity binding, MiniappUserKeyEntity key) {}
}
