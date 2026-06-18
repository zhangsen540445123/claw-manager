package com.clawbotforall.proxy;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.ProxyTarget;
import com.clawbotforall.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 将控制平面的 HTTP 请求代理到实例 Control UI。
 */
@RestController
public class ControlUiProxyController {

  private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
      "connection",
      "keep-alive",
      "proxy-authenticate",
      "proxy-authorization",
      "te",
      "trailer",
      "transfer-encoding",
      "upgrade",
      "host",
      "content-length"
  );

  private final InstanceCommandService instanceCommandService;
  private final OpenClawRuntime openClawRuntime;
  private final HttpClient httpClient;

  public ControlUiProxyController(
      InstanceCommandService instanceCommandService,
      OpenClawRuntime openClawRuntime
  ) {
    this.instanceCommandService = instanceCommandService;
    this.openClawRuntime = openClawRuntime;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  /**
   * 将 HTTP 请求代理到实例 Control UI。
   */

  @RequestMapping({"/proxy/{instanceId}", "/proxy/{instanceId}/**"})
  public ResponseEntity<byte[]> proxy(
      @PathVariable String instanceId,
      HttpServletRequest request,
      Authentication authentication
  ) throws IOException, InterruptedException {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);

    ProxyTarget target = openClawRuntime.resolveProxyTarget(instance);
    URI targetUri = ProxyUris.httpTargetUri(instanceId, request, target);
    HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
        .version(HttpClient.Version.HTTP_1_1)
        .timeout(Duration.ofSeconds(60))
        .method(request.getMethod(), bodyPublisher(request));

    copyRequestHeaders(request, builder);
    builder.header("Authorization", "Bearer " + instance.getGatewayToken());
    builder.header("X-Forwarded-Proto", request.isSecure() ? "https" : "http");
    builder.header("X-Forwarded-Host", request.getHeader("host") == null ? "" : request.getHeader("host"));

    HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    HttpHeaders headers = new HttpHeaders();
    response.headers().map().forEach((name, values) -> {
      if (!isHopByHop(name)) {
        headers.addAll(name, values);
      }
    });
    return ResponseEntity
        .status(response.statusCode())
        .headers(headers)
        .body(response.body());
  }

  static URI targetUri(String instanceId, HttpServletRequest request, ProxyTarget target) {
    return ProxyUris.httpTargetUri(instanceId, request, target);
  }

  private static HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) throws IOException {
    String method = request.getMethod().toUpperCase(Locale.ROOT);
    if ("GET".equals(method) || "HEAD".equals(method)) {
      return HttpRequest.BodyPublishers.noBody();
    }
    return HttpRequest.BodyPublishers.ofByteArray(request.getInputStream().readAllBytes());
  }

  private static void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
    Enumeration<String> headerNames = request.getHeaderNames();
    while (headerNames != null && headerNames.hasMoreElements()) {
      String name = headerNames.nextElement();
      if (isHopByHop(name) || "authorization".equalsIgnoreCase(name)) {
        continue;
      }
      Enumeration<String> values = request.getHeaders(name);
      while (values.hasMoreElements()) {
        builder.header(name, values.nextElement());
      }
    }
  }

  private static boolean isHopByHop(String name) {
    return HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT));
  }

  private static AuthenticatedAdmin requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin admin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
    return admin;
  }
}
