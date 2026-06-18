package com.clawbotforall.auth;

/**
 * 管理员账号的 API 安全响应模型。
 */
public record PublicAdmin(
    String id,
    String email,
    String name,
    String role,
    boolean mustChangePassword,
    String createdAt,
    String updatedAt
) {
  public static PublicAdmin from(AdminEntity admin) {
    if (admin == null) {
      return null;
    }
    return new PublicAdmin(
        admin.getId(),
        admin.getEmail(),
        admin.getName(),
        "admin",
        admin.isMustChangePassword(),
        admin.getCreatedAt(),
        admin.getUpdatedAt() == null ? admin.getCreatedAt() : admin.getUpdatedAt()
    );
  }

  public static PublicAdmin from(AuthenticatedAdmin admin) {
    if (admin == null) {
      return null;
    }
    return new PublicAdmin(
        admin.id(),
        admin.email(),
        admin.name(),
        "admin",
        admin.mustChangePassword(),
        admin.createdAt(),
        admin.updatedAt()
    );
  }
}
