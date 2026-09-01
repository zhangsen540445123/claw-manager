package com.clawbotforall.trace;

import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ModelCallAuditController {
  private final ModelCallAuditService service;
  private final OpenVikingBrokerTokenService tokens;

  public ModelCallAuditController(ModelCallAuditService service, OpenVikingBrokerTokenService tokens) {
    this.service = service;
    this.tokens = tokens;
  }

  @PostMapping("/api/internal/model-call-audits")
  public Map<String, Object> event(@RequestBody ModelCallAuditEventRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    requireToken(authorization);
    service.record(request);
    return Map.of("accepted", true);
  }

  private void requireToken(String authorization) {
    String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
    if (!tokens.matches(token)) throw new ApiException(HttpStatus.UNAUTHORIZED, "内部 model-call audit token 无效。");
  }
}
