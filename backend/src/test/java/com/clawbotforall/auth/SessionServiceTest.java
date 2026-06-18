package com.clawbotforall.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.config.ClawbotProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

  @Mock
  SessionMapper sessionMapper;

  @Test
  void createsSessionWithConfiguredCookieTtl() {
    SessionService service = newService(2);
    AdminEntity admin = new AdminEntity();
    admin.setId("admin_1");

    SessionEntity session = service.createSession(admin);

    ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
    verify(sessionMapper).insert(captor.capture());
    assertThat(session.getId()).startsWith("sess_");
    assertThat(captor.getValue().getAdminId()).isEqualTo("admin_1");
    assertThat(captor.getValue().getExpiresAt()).isNotBlank();
  }

  @Test
  void readsWritesAndClearsSessionCookie() {
    SessionService service = newService(3);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("other", "ignored"), new Cookie("sid", "sess_123"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThat(service.readSessionId(request)).contains("sess_123");

    service.writeSessionCookie(response, "sess_123");
    assertThat(response.getHeader("Set-Cookie"))
        .contains("sid=sess_123")
        .contains("Max-Age=259200")
        .contains("HttpOnly")
        .contains("SameSite=Lax");

    MockHttpServletResponse clearResponse = new MockHttpServletResponse();
    service.clearSessionCookie(clearResponse);
    assertThat(clearResponse.getHeader("Set-Cookie"))
        .contains("sid=")
        .contains("Max-Age=0");
  }

  @Test
  void ignoresBlankSessionIds() {
    SessionService service = newService(1);

    assertThat(service.findAdminBySessionId(" ")).isEmpty();
    service.deleteSession("");

    verify(sessionMapper, never()).findAdminBySessionId(any(), any());
    verify(sessionMapper, never()).deleteById(any());
  }

  @Test
  void returnsAdminForValidSession() {
    SessionService service = newService(1);
    AdminEntity admin = new AdminEntity();
    admin.setId("admin_1");
    when(sessionMapper.findAdminBySessionId(org.mockito.ArgumentMatchers.eq("sess_1"), any()))
        .thenReturn(admin);

    assertThat(service.findAdminBySessionId("sess_1")).contains(admin);
  }

  private SessionService newService(int ttlDays) {
    return new SessionService(
        new ClawbotProperties(null, null, new ClawbotProperties.Security("sid", ttlDays), null),
        sessionMapper
    );
  }
}
