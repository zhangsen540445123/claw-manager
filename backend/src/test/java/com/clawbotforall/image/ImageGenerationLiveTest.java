package com.clawbotforall.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Explicit, paid integration check. Enable with IMAGE_API_LIVE_TEST=true. */
class ImageGenerationLiveTest {
  @Test
  void callsConfiguredOpenAiCompatibleImageApi() throws Exception {
    assumeTrue("true".equalsIgnoreCase(System.getenv("IMAGE_API_LIVE_TEST")));
    String baseUrl = required("IMAGE_API_BASE_URL").replaceAll("/+$", "");
    String key = required("IMAGE_API_KEY");
    String model = System.getenv().getOrDefault("IMAGE_API_MODEL", "gpt-image-2");
    ObjectMapper mapper = new ObjectMapper();
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    HttpResponse<String> models = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
        .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(models.statusCode()).isBetween(200, 299);
    JsonNode modelData = mapper.readTree(models.body()).path("data");
    assertThat(modelData.isArray()).isTrue();
    String body = mapper.createObjectNode().put("model", model).put("prompt", "一张白色背景、中央只有一个红色圆形的极简测试图，不包含文字").put("n", 1).put("size", "1024x1024").put("quality", "auto").put("response_format", "b64_json").toString();
    long started = System.nanoTime();
    HttpResponse<String> generated = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/images/generations"))
        .timeout(Duration.ofMinutes(4)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(generated.statusCode()).isBetween(200, 299);
    JsonNode item = mapper.readTree(generated.body()).path("data").path(0);
    byte[] image = item.hasNonNull("b64_json") ? Base64.getDecoder().decode(item.get("b64_json").asText()) : client.send(
        HttpRequest.newBuilder(URI.create(item.path("url").asText())).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body();
    assertThat(image.length).isBetween(1, 10 * 1024 * 1024);
    assertThat(image[0] & 0xff).isIn(0x89, 0xff, 0x52);
    System.out.printf("live image API passed: mime-header=%02x bytes=%d elapsedMs=%d%n", image[0] & 0xff, image.length, (System.nanoTime() - started) / 1_000_000);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assumeTrue(value != null && !value.isBlank(), name + " is required");
    return value;
  }
}
