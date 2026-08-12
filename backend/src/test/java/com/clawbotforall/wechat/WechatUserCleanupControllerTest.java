package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class WechatUserCleanupControllerTest {
  @Mock WechatUserQueryService queryService;
  @Mock WechatUserCleanupService cleanupService;
  private WechatUserCleanupController controller;

  @BeforeEach
  void setUp() {
    controller = new WechatUserCleanupController(queryService, cleanupService);
  }

  @Test
  void listsUnifiedWechatUsersForAdmin() {
    PublicWechatUser user = new PublicWechatUser(
        "inst-1", "实例一", "running", "account-1", "13500000000", "wechat-1",
        "user_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "wx-memory", "", "", null, null,
        "unknown", null, null, null, null,
        null, null, null, false, null, "active", null, null, false, null, List.of());
    when(queryService.listUsers()).thenReturn(List.of(user));

    Map<String, Object> response = controller.listUsers(authentication());

    assertThat(response.get("users")).isEqualTo(List.of(user));
  }

  @Test
  void retriesFailedCleanupWithoutExposingIdentitySnapshot() {
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId("op-1");
    operation.setInstanceId("inst-1");
    operation.setPhone("13500000000");
    operation.setStatus("cleanup_failed");
    operation.setStage("routing_deleted");
    operation.setAttemptCount(2);
    operation.setLastError("清理失败");
    when(cleanupService.retry("op-1")).thenReturn(operation);

    Map<String, Object> response = controller.retry("op-1", authentication());

    verify(cleanupService).retry("op-1");
    PublicWechatUserCleanupOperation publicOperation =
        (PublicWechatUserCleanupOperation) response.get("operation");
    assertThat(publicOperation.operationId()).isEqualTo("op-1");
    assertThat(publicOperation.retryable()).isTrue();
    assertThat(response.toString()).doesNotContain("13500000000");
  }

  @Test
  void rejectsAnonymousUser() {
    assertThatThrownBy(() -> controller.listUsers(null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("请先登录");
  }

  private static TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new AuthenticatedAdmin("admin-1", "admin@example.test", "Admin", false, "now", "now"), null);
  }
}
