package com.clawbotforall.miniapp;

import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MiniappBridgeInternalController {
  private final OpenVikingBrokerTokenService tokenService;
  private final MiniappBridgeService bridgeService;

  public MiniappBridgeInternalController(OpenVikingBrokerTokenService tokenService, MiniappBridgeService bridgeService) {
    this.tokenService = tokenService;
    this.bridgeService = bridgeService;
  }

  @PostMapping("/api/internal/miniapp-bridge/actions/{actionKey}")
  public Map<String, Object> execute(
      @PathVariable String actionKey,
      @RequestBody(required = false) MiniappBridgeRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
  ) {
    String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
    if (!tokenService.matches(token)) throw new ApiException(HttpStatus.UNAUTHORIZED, "Miniapp Bridge token 无效。");
    return Map.of("result", bridgeService.execute(actionKey, request));
  }
}
