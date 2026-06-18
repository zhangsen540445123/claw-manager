package com.clawbotforall.model;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向前端提供可用模型 Provider 定义。
 */
@RestController
@RequestMapping("/api/model-providers")
public class ModelProviderController {

  private final ModelProviderService modelProviderService;

  public ModelProviderController(ModelProviderService modelProviderService) {
    this.modelProviderService = modelProviderService;
  }

  /**
   * 返回已配置的模型 Provider 目录。
   */

  @GetMapping
  public Map<String, Object> listProviders() {
    return Map.of("providers", modelProviderService.listProviders());
  }
}
