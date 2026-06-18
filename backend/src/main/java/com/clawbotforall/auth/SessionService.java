package com.clawbotforall.auth;

import com.clawbotforall.config.ClawbotProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 创建、读取和销毁浏览器会话。
 */
@Service
public class SessionService {

  private final ClawbotProperties properties;
  private final SessionMapper sessionMapper;

  public SessionService(ClawbotProperties properties, SessionMapper sessionMapper) {
    this.properties = properties;
    this.sessionMapper = sessionMapper;
  }

  /**
   * 为管理员创建浏览器会话。
   */

  @Transactional
  public SessionEntity createSession(AdminEntity admin) {
    Instant createdAt = Instant.now();
    SessionEntity session = new SessionEntity();
    session.setId(randomSessionId());
    session.setAdminId(admin.getId());
    session.setCreatedAt(createdAt.toString());
    session.setExpiresAt(createdAt.plus(Duration.ofDays(properties.security().sessionTtlDays())).toString());
    sessionMapper.insert(session);
    return session;
  }

  /**
   * 根据会话 ID 查询仍在有效期内的管理员。
   */

  @Transactional(readOnly = true)
  public Optional<AdminEntity> findAdminBySessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(sessionMapper.findAdminBySessionId(sessionId, Instant.now().toString()));
  }

  /**
   * 删除浏览器会话 Token。
   */

  @Transactional
  public void deleteSession(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      sessionMapper.deleteById(sessionId);
    }
  }

  /**
   * 从请求 Cookie 中读取浏览器会话 ID。
   */

  public Optional<String> readSessionId(HttpServletRequest request) {
    if (request.getCookies() == null) {
      return Optional.empty();
    }
    String cookieName = properties.security().sessionCookieName();
    for (Cookie cookie : request.getCookies()) {
      if (cookieName.equals(cookie.getName())) {
        return Optional.ofNullable(cookie.getValue());
      }
    }
    return Optional.empty();
  }

  /**
   * 将浏览器会话 ID 写入 HttpOnly Cookie。
   */

  public void writeSessionCookie(HttpServletResponse response, String sessionId) {
    int ttlSeconds = properties.security().sessionTtlDays() * 24 * 60 * 60;
    ResponseCookie cookie = ResponseCookie.from(properties.security().sessionCookieName(), sessionId)
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .maxAge(Duration.ofSeconds(ttlSeconds))
        .build();
    response.addHeader("Set-Cookie", cookie.toString());
  }

  /**
   * 通过过期 Cookie 清除浏览器会话。
   */

  public void clearSessionCookie(HttpServletResponse response) {
    ResponseCookie cookie = ResponseCookie.from(properties.security().sessionCookieName(), "")
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .maxAge(Duration.ZERO)
        .build();
    response.addHeader("Set-Cookie", cookie.toString());
  }

  private static String randomSessionId() {
    return "sess_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
  }
}
