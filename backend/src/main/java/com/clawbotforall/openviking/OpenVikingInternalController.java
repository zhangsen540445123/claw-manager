package com.clawbotforall.openviking;

import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenVikingInternalController {

  private final OpenVikingBrokerTokenService brokerTokenService;
  private final OpenVikingUserKeyService userKeyService;

  public OpenVikingInternalController(
      OpenVikingBrokerTokenService brokerTokenService,
      OpenVikingUserKeyService userKeyService
  ) {
    this.brokerTokenService = brokerTokenService;
    this.userKeyService = userKeyService;
  }

  @PostMapping("/api/internal/openviking/users/resolve")
  public Map<String, Object> resolve(
      @RequestBody(required = false) OpenVikingUserResolveRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
  ) {
    String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
    if (!brokerTokenService.matches(token)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "OpenViking broker token 无效。");
    }
    OpenVikingResolvedUserKey resolved = userKeyService.resolve(request);
    return Map.of("user", resolved);
  }
}
