package com.clawbotforall.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import java.util.HashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;

class WebSocketConfigTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void determinesUserFromSecurityContextDuringHandshake() {
    AuthenticatedAdmin admin = admin();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null));

    assertThat(determineUser()).isEqualTo(admin);
  }

  @Test
  void fallsBackToRequestPrincipalDuringHandshake() {
    AuthenticatedAdmin admin = admin();
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPrincipal()).thenReturn(admin);

    assertThat(determineUser(request)).isEqualTo(admin);
  }

  private static java.security.Principal determineUser() {
    return determineUser(mock(ServerHttpRequest.class));
  }

  private static java.security.Principal determineUser(ServerHttpRequest request) {
    return new WebSocketConfig.SecurityContextHandshakeHandler()
        .determineUser(request, mock(WebSocketHandler.class), new HashMap<>());
  }

  private static AuthenticatedAdmin admin() {
    return new AuthenticatedAdmin(
        "admin_123",
        "test@example.com",
        "tester",
        false,
        "2026-06-15T00:00:00Z",
        "2026-06-15T00:00:00Z"
    );
  }
}
