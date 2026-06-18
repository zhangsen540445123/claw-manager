package com.clawbotforall.auth;

import com.clawbotforall.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处理管理员登录、退出、改密和会话查询。
 */
@RestController
@RequestMapping("/api")
public class AuthController {

  private final AdminMapper adminMapper;
  private final PasswordHasher passwordHasher;
  private final SessionService sessionService;

  public AuthController(
      AdminMapper adminMapper,
      PasswordHasher passwordHasher,
      SessionService sessionService
  ) {
    this.adminMapper = adminMapper;
    this.passwordHasher = passwordHasher;
    this.sessionService = sessionService;
  }

  /**
   * 返回当前登录管理员；没有会话时返回空值。
   */
  @GetMapping("/session")
  public Map<String, Object> session(HttpServletRequest request) {
    AdminEntity admin = sessionService.readSessionId(request)
        .flatMap(sessionService::findAdminBySessionId)
        .orElse(null);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("user", PublicAdmin.from(admin));
    return response;
  }

  /**
   * 认证管理员并创建浏览器会话。
   */
  @PostMapping("/login")
  @Transactional
  public Map<String, Object> login(
      @RequestBody LoginRequest request,
      HttpServletResponse response
  ) {
    String email = normalizeEmail(request.email());
    String password = request.password() == null ? "" : request.password();
    AdminEntity admin = adminMapper.findByEmail(email);
    if (admin == null || !passwordHasher.verify(password, admin.getPasswordSalt(), admin.getPasswordHash())) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误。");
    }

    SessionEntity session = sessionService.createSession(admin);
    sessionService.writeSessionCookie(response, session.getId());
    return Map.of("user", PublicAdmin.from(admin));
  }

  /**
   * 销毁当前浏览器会话并清除会话 Cookie。
   */
  @PostMapping("/logout")
  public Map<String, Object> logout(
      HttpServletRequest request,
      HttpServletResponse response
  ) {
    sessionService.readSessionId(request).ifPresent(sessionService::deleteSession);
    sessionService.clearSessionCookie(response);
    return Map.of("ok", true);
  }

  /**
   * 修改当前管理员密码，并清除强制改密标记。
   */
  @PostMapping("/change-password")
  @Transactional
  public Map<String, Object> changePassword(
      @RequestBody ChangePasswordRequest request,
      Authentication authentication
  ) {
    AuthenticatedAdmin currentAdmin = requireAdmin(authentication);
    String nextPassword = request.newPassword() == null ? "" : request.newPassword();
    if (nextPassword.length() < 8) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "新密码至少 8 位。");
    }

    AdminEntity admin = adminMapper.findByEmail(currentAdmin.email());
    if (admin == null) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }

    String currentPassword = request.currentPassword() == null ? "" : request.currentPassword();
    if (!admin.isMustChangePassword() && !passwordHasher.verify(currentPassword, admin.getPasswordSalt(), admin.getPasswordHash())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "当前密码不正确。");
    }

    HashedPassword hashedPassword = passwordHasher.hash(nextPassword);
    adminMapper.updatePassword(
        admin.getId(),
        hashedPassword.hash(),
        hashedPassword.salt(),
        false,
        Instant.now().toString()
    );
    AdminEntity latestAdmin = adminMapper.findByEmail(admin.getEmail());
    return Map.of("user", PublicAdmin.from(latestAdmin));
  }

  private static AuthenticatedAdmin requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin admin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
    return admin;
  }

  private static String normalizeEmail(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  public record LoginRequest(String email, String password) {}

  public record ChangePasswordRequest(String currentPassword, String newPassword) {}
}
