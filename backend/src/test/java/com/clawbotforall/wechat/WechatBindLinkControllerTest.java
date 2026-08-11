package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class WechatBindLinkControllerTest {

  @Mock
  WechatBindLinkService bindLinkService;

  WechatBindLinkController controller;

  @BeforeEach
  void setUp() {
    controller = new WechatBindLinkController(bindLinkService);
  }

  @Test
  void retryCleanupRequiresAdmin() {
    assertThatThrownBy(() -> controller.retryCleanup(
        "token_1",
        null,
        new MockHttpServletRequest()
    ))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("请先登录");
  }

  @Test
  void cancelCleanupRequiresAdmin() {
    assertThatThrownBy(() -> controller.cancelCleanup(
        "token_1",
        null,
        new MockHttpServletRequest()
    ))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("请先登录");
  }

  @Test
  void adminCanRetryCleanup() {
    PublicWechatBindLink link = new PublicWechatBindLink(
        "token_1", "existing", "cleaning", "13572873189", "inst_1", "实例一",
        null, "", "", null, false, "正在清理", "validated", false, "",
        "", "", "", "", "清理迁移中", "老用户", "/bind/token_1"
    );
    when(bindLinkService.retryCleanup("token_1", "http://localhost"))
        .thenReturn(link);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(80);

    Map<String, Object> result = controller.retryCleanup("token_1", authentication(), request);

    verify(bindLinkService).retryCleanup("token_1", "http://localhost");
    assertThat(result.get("link")).isSameAs(link);
  }

  @Test
  void adminCanCancelFailedCleanup() {
    PublicWechatBindLink link = new PublicWechatBindLink(
        "token_1", "existing", "revoked", "13572873189", "inst_1", "实例一",
        null, "", "", null, false, "已取消", "channels_stopped", false, "",
        "", "", "", "", "已失效", "老用户", "/bind/token_1"
    );
    when(bindLinkService.cancelCleanup("token_1", "http://localhost"))
        .thenReturn(link);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(80);

    Map<String, Object> result = controller.cancelCleanup("token_1", authentication(), request);

    verify(bindLinkService).cancelCleanup("token_1", "http://localhost");
    assertThat(result.get("link")).isSameAs(link);
  }

  private static TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new AuthenticatedAdmin(
            "admin_1", "admin@example.test", "Admin", false,
            "2026-06-20T00:00:00Z", "2026-06-20T00:00:00Z"
        ),
        null
    );
  }
}
