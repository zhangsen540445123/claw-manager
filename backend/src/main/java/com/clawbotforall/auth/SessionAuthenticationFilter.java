package com.clawbotforall.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 根据配置的会话 Cookie 认证浏览器请求。
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  private final SessionService sessionService;

  public SessionAuthenticationFilter(SessionService sessionService) {
    this.sessionService = sessionService;
  }

  /**
   * 在继续执行 Servlet 过滤器链前尝试完成请求认证。
   */

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      sessionService.readSessionId(request)
          .flatMap(sessionService::findAdminBySessionId)
          .map(AuthenticatedAdmin::from)
          .ifPresent(admin -> {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                admin,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
          });
    }

    filterChain.doFilter(request, response);
  }
}
