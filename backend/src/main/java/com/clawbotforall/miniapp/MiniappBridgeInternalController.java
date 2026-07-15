package com.clawbotforall.miniapp;

import com.clawbotforall.openviking.OpenVikingBrokerTokenService;
import com.clawbotforall.image.ImageGenerationService;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MiniappBridgeInternalController {
  private final OpenVikingBrokerTokenService tokenService;
  private final MiniappBridgeService bridgeService;
  private final MiniappArtifactService artifactService;
  private final ImageGenerationService imageGenerationService;

  public MiniappBridgeInternalController(OpenVikingBrokerTokenService tokenService, MiniappBridgeService bridgeService,
      MiniappArtifactService artifactService, ImageGenerationService imageGenerationService) {
    this.tokenService = tokenService;
    this.bridgeService = bridgeService;
    this.artifactService = artifactService;
    this.imageGenerationService = imageGenerationService;
  }

  @PostMapping("/api/internal/miniapp-bridge/image-generation")
  public Map<String, Object> generateImage(@RequestBody MiniappImageGenerationRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    requireToken(authorization);
    return imageGenerationService.generate(request.instanceId(), request.prompt(), request.size(), request.quality());
  }

  @PostMapping("/api/internal/miniapp-bridge/artifacts/html")
  public Map<String, Object> publishHtml(@RequestBody MiniappArtifactHtmlRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    requireToken(authorization);
    return artifactService.publishHtml(request);
  }

  @PostMapping(value = "/api/internal/miniapp-bridge/artifacts/images", consumes = "multipart/form-data")
  public Map<String, Object> publishImage(
      @RequestParam String instanceId, @RequestParam String requesterSenderId,
      @RequestParam(required = false) String requestId, @RequestParam(required = false) String title,
      @RequestParam(required = false) String description, @RequestParam("image") MultipartFile image,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    requireToken(authorization);
    return artifactService.publishImage(instanceId, requesterSenderId, requestId, title, description, image);
  }

  private void requireToken(String authorization) {
    String token = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
    if (!tokenService.matches(token)) throw new ApiException(HttpStatus.UNAUTHORIZED, "Miniapp Bridge token 无效。");
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
