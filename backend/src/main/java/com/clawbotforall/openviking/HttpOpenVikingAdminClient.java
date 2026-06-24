package com.clawbotforall.openviking;

import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class HttpOpenVikingAdminClient implements OpenVikingAdminClient {

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public HttpOpenVikingAdminClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public String registerUser(String baseUrl, String rootApiKey, String accountId, String openvikingUserId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user_id", openvikingUserId);
    body.put("role", "user");
    return postForUserKey(
        baseUrl,
        rootApiKey,
        "/api/v1/admin/accounts/" + encode(accountId) + "/users",
        body,
        "注册 OpenViking user"
    );
  }

  @Override
  public String regenerateUserKey(String baseUrl, String rootApiKey, String accountId, String openvikingUserId) {
    return postForUserKey(
        baseUrl,
        rootApiKey,
        "/api/v1/admin/accounts/" + encode(accountId) + "/users/" + encode(openvikingUserId) + "/key",
        Map.of(),
        "生成 OpenViking user key"
    );
  }

  private String postForUserKey(String baseUrl, String rootApiKey, String path, Map<String, Object> body, String action) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(trimBaseUrl(baseUrl) + path))
          .timeout(Duration.ofSeconds(15))
          .header("Content-Type", "application/json")
          .header("X-API-Key", rootApiKey)
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode payload = response.body() == null || response.body().isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(response.body());
      if (response.statusCode() < 200 || response.statusCode() >= 300 || "error".equals(payload.path("status").asText())) {
        throw toApiException(response.statusCode(), payload, action);
      }
      String userKey = firstText(payload, "result.user_key", "result.api_key", "result.key", "user_key", "api_key", "key");
      if (userKey.isBlank()) {
        throw new ApiException(HttpStatus.BAD_GATEWAY, action + "失败：OpenViking 未返回 user key。");
      }
      return userKey;
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, action + "失败：" + message(error));
    }
  }

  private ApiException toApiException(int statusCode, JsonNode payload, String action) {
    HttpStatus status = statusCode == 401 || statusCode == 403 ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_GATEWAY;
    String code = payload.path("error").path("code").asText("");
    String detail = payload.path("error").path("message").asText("");
    if (detail.isBlank()) {
      detail = "HTTP " + statusCode;
    }
    String suffix = code.isBlank() ? detail : code + ": " + detail;
    return new ApiException(status, action + "失败：" + suffix);
  }

  private static String firstText(JsonNode payload, String... paths) {
    for (String path : paths) {
      JsonNode current = payload;
      for (String segment : path.split("\\.")) {
        current = current.path(segment);
      }
      String value = current.asText("");
      if (!value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String trimBaseUrl(String baseUrl) {
    String normalized = baseUrl == null ? "" : baseUrl.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String message(Throwable error) {
    return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
  }
}
