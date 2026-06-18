package com.clawbotforall.auth;

import java.security.Principal;

/**
 * 管理员登录成功后暴露给 Spring Security 和 WebSocket 的安全主体。
 */
public record AuthenticatedAdmin(
    String id,
    String email,
    String name,
    boolean mustChangePassword,
    String createdAt,
    String updatedAt
) implements Principal {

  @Override
  public String getName() {
    return id;
  }

  public static AuthenticatedAdmin from(AdminEntity admin) {
    return new AuthenticatedAdmin(
        admin.getId(),
        admin.getEmail(),
        admin.getName(),
        admin.isMustChangePassword(),
        admin.getCreatedAt(),
        admin.getUpdatedAt() == null ? admin.getCreatedAt() : admin.getUpdatedAt()
    );
  }
}
