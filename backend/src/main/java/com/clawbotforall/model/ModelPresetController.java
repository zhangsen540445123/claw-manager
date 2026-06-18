package com.clawbotforall.model;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供模型预设和模型 Provider 定义 API。
 */
@RestController
@RequestMapping("/api")
public class ModelPresetController {

  private final ModelPresetService modelPresetService;

  public ModelPresetController(ModelPresetService modelPresetService) {
    this.modelPresetService = modelPresetService;
  }

  /**
   * 以公共响应形式返回全部模型预设。
   */

  @GetMapping("/model-presets")
  public Map<String, Object> listPresets() {
    return Map.of("presets", modelPresetService.listPublicPresets());
  }

  /**
   * 根据规范化后的管理员输入创建模型预设。
   */

  @PostMapping("/admin/model-presets")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createPreset(
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("preset", modelPresetService.createPreset(payload));
  }

  /**
   * 更新模型预设，并规范化密钥处理。
   */

  @PutMapping("/admin/model-presets/{presetId}")
  public Map<String, Object> updatePreset(
      @PathVariable String presetId,
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    ModelPresetUpdateResult result = modelPresetService.updatePreset(presetId, payload);
    return Map.of("preset", result.preset(), "sync", result.sync());
  }

  @GetMapping("/admin/model-presets/{presetId}/usage")
  public Map<String, Object> presetUsage(
      @PathVariable String presetId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("usage", modelPresetService.usage(presetId));
  }

  /**
   * 在不会破坏预设目录时删除预设。
   */

  @DeleteMapping("/admin/model-presets/{presetId}")
  public Map<String, Object> deletePreset(
      @PathVariable String presetId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    modelPresetService.deletePreset(presetId);
    return Map.of("ok", true);
  }

  @PostMapping("/admin/model-presets/{presetId}/default")
  public Map<String, Object> setDefault(
      @PathVariable String presetId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    modelPresetService.setDefault(presetId);
    return Map.of("ok", true);
  }

  private static AuthenticatedAdmin requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin admin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
    return admin;
  }
}
