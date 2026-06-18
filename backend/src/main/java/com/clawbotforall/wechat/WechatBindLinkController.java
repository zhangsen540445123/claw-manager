package com.clawbotforall.wechat;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.web.RequestOrigins;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供管理员扫码链接和公开扫码绑定 API。
 */
@RestController
public class WechatBindLinkController {

  private final WechatBindLinkService bindLinkService;

  public WechatBindLinkController(WechatBindLinkService bindLinkService) {
    this.bindLinkService = bindLinkService;
  }

  /**
   * 管理员生成新用户或老用户扫码链接。
   */
  @PostMapping("/api/admin/wechat-bind-links")
  public Map<String, Object> createBindLink(
      @RequestBody(required = false) WechatBindLinkService.CreateBindLinkRequest request,
      Authentication authentication,
      HttpServletRequest servletRequest
  ) {
    AuthenticatedAdmin admin = requireAdmin(authentication);
    PublicWechatBindLink link = bindLinkService.createLink(admin, request, RequestOrigins.resolve(servletRequest));
    return Map.of("link", link);
  }

  /**
   * 管理员按手机号查询已绑定微信账号。
   */
  @GetMapping("/api/admin/wechat-bindings")
  public Map<String, Object> findBindingByPhone(
      @RequestParam String phone,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    WechatPairedAccountEntity account = bindLinkService.findBindingByPhone(phone);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("binding", account == null ? null : Map.of(
        "accountId", account.getAccountId(),
        "phone", account.getPhone(),
        "instanceId", account.getInstanceId(),
        "wechatUserId", defaultString(account.getWechatUserId()),
        "remark", defaultString(account.getRemark()),
        "baseUrl", defaultString(account.getBaseUrl()),
        "savedAt", account.getSavedAt() == null ? "" : account.getSavedAt(),
        "boundAt", account.getBoundAt() == null ? "" : account.getBoundAt(),
        "updatedAt", account.getUpdatedAt() == null ? "" : account.getUpdatedAt()
    ));
    return response;
  }

  /**
   * 公开读取扫码链接状态。
   */
  @GetMapping("/api/public/wechat-bind-links/{token}")
  public Map<String, Object> publicStatus(
      @PathVariable String token,
      HttpServletRequest request
  ) {
    return Map.of("link", bindLinkService.getPublicStatus(token, RequestOrigins.resolve(request)));
  }

  /**
   * 新用户提交手机号后获取二维码。
   */
  @PostMapping("/api/public/wechat-bind-links/{token}/phone")
  public Map<String, Object> submitPhone(
      @PathVariable String token,
      @RequestBody(required = false) PhoneRequest body,
      HttpServletRequest request
  ) {
    String phone = body == null ? "" : body.phone();
    return Map.of("link", bindLinkService.submitPhone(token, phone, RequestOrigins.resolve(request)));
  }

  /**
   * 重新生成当前扫码链接二维码。
   */
  @PostMapping("/api/public/wechat-bind-links/{token}/qr/refresh")
  public Map<String, Object> refreshQr(
      @PathVariable String token,
      HttpServletRequest request
  ) {
    return Map.of("link", bindLinkService.refreshQr(token, RequestOrigins.resolve(request)));
  }

  private static AuthenticatedAdmin requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin admin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
    return admin;
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  public record PhoneRequest(String phone) {}
}
