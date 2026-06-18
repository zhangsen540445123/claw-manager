package com.clawbotforall.proxy;

import com.clawbotforall.runtime.ProxyTarget;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

/**
 * 将控制平面代理路径转换为运行时目标 URI 的工具方法。
 */
final class ProxyUris {

  private ProxyUris() {}

  static URI httpTargetUri(String instanceId, HttpServletRequest request, ProxyTarget target) {
    return targetUri("http", instanceId, request.getContextPath(), request.getRequestURI(), request.getQueryString(), target);
  }

  static URI wsTargetUri(String instanceId, URI sourceUri, ProxyTarget target) {
    String rawQuery = sourceUri.getRawQuery();
    return targetUri("ws", instanceId, "", sourceUri.getPath(), rawQuery, target);
  }

  static URI targetUri(
      String scheme,
      String instanceId,
      String contextPath,
      String requestUri,
      String rawQuery,
      ProxyTarget target
  ) {
    String prefix = (contextPath == null ? "" : contextPath) + "/proxy/" + instanceId;
    String path = requestUri != null && requestUri.startsWith(prefix) ? requestUri.substring(prefix.length()) : "";
    if (path.isBlank()) {
      path = "/";
    }
    String targetText = scheme + "://" + target.host() + ":" + target.port() + path
        + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery);
    return URI.create(targetText);
  }
}
