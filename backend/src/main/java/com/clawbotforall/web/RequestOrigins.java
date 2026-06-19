package com.clawbotforall.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 在代理后方推导公共请求 Origin 的工具方法。
 */
public final class RequestOrigins {

  private RequestOrigins() {}

  /**
   * 根据反向代理头或当前请求推导外部可访问 Origin。
   */

  public static String resolve(HttpServletRequest request) {
    String proto = firstHeader(request, "x-forwarded-proto");
    if (proto == null || proto.isBlank()) {
      proto = request.isSecure() ? "https" : "http";
    }
    String host = firstHeader(request, "x-forwarded-host");
    if (host == null || host.isBlank()) {
      host = request.getHeader("host");
    }
    if (host == null || host.isBlank()) {
      int port = request.getServerPort();
      boolean defaultPort = ("http".equals(proto) && port == 80)
          || ("https".equals(proto) && port == 443);
      host = defaultPort ? request.getServerName() : request.getServerName() + ":" + port;
    }
    return proto.trim() + "://" + host.trim();
  }

  private static String firstHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null) {
      return null;
    }
    return value.split(",")[0].trim();
  }
}
