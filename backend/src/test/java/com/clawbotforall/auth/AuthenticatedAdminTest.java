package com.clawbotforall.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticatedAdminTest {

  @Test
  void exposesAdminIdAsPrincipalName() {
    AuthenticatedAdmin admin = new AuthenticatedAdmin(
        "admin_1",
        "admin@example.com",
        "平台管理员",
        false,
        "2026-06-18T00:00:00Z",
        "2026-06-18T00:00:00Z"
    );

    assertThat(admin.getName()).isEqualTo("admin_1");
  }
}
