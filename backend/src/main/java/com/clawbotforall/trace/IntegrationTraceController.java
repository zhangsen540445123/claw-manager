package com.clawbotforall.trace;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class IntegrationTraceController {
  private final IntegrationTraceService service;
  private final OpenVikingBrokerTokenService tokens;
  public IntegrationTraceController(IntegrationTraceService service, OpenVikingBrokerTokenService tokens) { this.service=service; this.tokens=tokens; }

  @PostMapping("/api/internal/integration-traces/events")
  public Map<String, Object> event(@RequestBody IntegrationTraceEventRequest request,
      @RequestHeader(value=HttpHeaders.AUTHORIZATION, required=false) String authorization,
      @RequestHeader(value="X-CM-Trace-Id", required=false) String traceId) {
    requireToken(authorization); service.record(request, traceId); return Map.of("accepted", true);
  }

  @GetMapping("/api/admin/integration-traces")
  public Map<String,Object> list(@RequestParam(defaultValue="") String instanceId, @RequestParam(defaultValue="") String channel,
      @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="") String component,
      @RequestParam(defaultValue="") String stage, @RequestParam(defaultValue="") String diagnosisCode,
      @RequestParam(defaultValue="") String from, @RequestParam(defaultValue="") String to,
      @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, Authentication auth) {
    requireAdmin(auth); return service.list(instanceId, channel, status, component, stage, diagnosisCode, from, to, page, size);
  }

  @GetMapping("/api/admin/integration-traces/{traceId}")
  public Map<String,Object> detail(@PathVariable String traceId, Authentication auth) { requireAdmin(auth); return service.detail(traceId); }

  private void requireToken(String authorization) { String token=authorization==null?"":authorization.replaceFirst("(?i)^Bearer\\s+","").trim(); if(!tokens.matches(token)) throw new ApiException(HttpStatus.UNAUTHORIZED,"内部 trace token 无效。"); }
  private void requireAdmin(Authentication auth) { if(auth==null || !(auth.getPrincipal() instanceof AuthenticatedAdmin)) throw new ApiException(HttpStatus.UNAUTHORIZED,"请先登录。"); }
}
