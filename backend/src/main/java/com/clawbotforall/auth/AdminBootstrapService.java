package com.clawbotforall.auth;

import com.clawbotforall.config.ClawbotProperties;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用启动时确保配置的管理员账号存在。
 */
@Component
public class AdminBootstrapService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

  private final ClawbotProperties properties;
  private final AdminMapper adminMapper;
  private final PasswordHasher passwordHasher;

  public AdminBootstrapService(
      ClawbotProperties properties,
      AdminMapper adminMapper,
      PasswordHasher passwordHasher
  ) {
    this.properties = properties;
    this.adminMapper = adminMapper;
    this.passwordHasher = passwordHasher;
  }

  /**
   * 在 Spring 上下文就绪后初始化必要的应用数据。
   */

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    ClawbotProperties.Admin adminConfig = properties.admin();
    boolean adminEnvProvided = hasText(adminConfig.email()) || hasText(adminConfig.name()) || hasText(adminConfig.password());
    if (!adminEnvProvided) {
      return;
    }

    String email = normalizeEmail(adminConfig.email());
    String name = hasText(adminConfig.name()) ? adminConfig.name().trim() : "平台管理员";
    String password = adminConfig.password() == null ? "" : adminConfig.password();

    if (!hasText(email) || !hasText(name) || password.length() < 8) {
      throw new IllegalStateException("管理员账号环境变量无效。请配置 ADMIN_EMAIL、ADMIN_NAME、ADMIN_PASSWORD，且密码至少 8 位。");
    }

    String now = Instant.now().toString();
    AdminEntity existing = adminMapper.findByEmail(email);
    if (existing != null) {
      adminMapper.updateProfile(existing.getId(), hasText(existing.getName()) ? existing.getName() : name, now);
      log.info("管理员账号已存在：{}", email);
      return;
    }

    HashedPassword hashedPassword = passwordHasher.hash(password);
    AdminEntity newAdmin = new AdminEntity();
    newAdmin.setId("admin_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    newAdmin.setEmail(email);
    newAdmin.setName(name);
    newAdmin.setMustChangePassword(true);
    newAdmin.setPasswordHash(hashedPassword.hash());
    newAdmin.setPasswordSalt(hashedPassword.salt());
    newAdmin.setCreatedAt(now);
    newAdmin.setUpdatedAt(now);
    adminMapper.insert(newAdmin);
    log.info("管理员账号已初始化：{}", email);
  }

  private static String normalizeEmail(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
