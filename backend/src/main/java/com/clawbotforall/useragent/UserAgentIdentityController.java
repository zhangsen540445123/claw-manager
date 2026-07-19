package com.clawbotforall.useragent;

import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import com.clawbotforall.web.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserAgentIdentityController {
  private final OpenVikingBrokerTokenService tokenService;
  private final UserAgentIdentityService identityService;

  public UserAgentIdentityController(
      OpenVikingBrokerTokenService tokenService,
      UserAgentIdentityService identityService
  ) {
    this.tokenService = tokenService;
    this.identityService = identityService;
  }

  @PostMapping("/api/internal/user-agents/resolve")
  public UserAgentIdentityResult resolve(
      @RequestBody(required = false) UserAgentResolveRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
  ) {
    String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
    if (!tokenService.matches(token)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "OpenViking broker token 无效。");
    }
    if (request == null) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "用户 Agent 身份请求不能为空。");
    }
    return identityService.resolve(request.getInstanceId(), request.getWechatUserId());
  }
}
