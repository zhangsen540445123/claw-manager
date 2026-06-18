package com.clawbotforall.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 提供受支持模型 Provider 变体目录。
 */
@Service
public class ModelProviderService {

  private static final String PROVIDERS_RESOURCE = "model-providers.json";

  private final List<ModelProviderDefinition> providers;
  private final Map<String, ModelProviderDefinition> providersByKey;

  public ModelProviderService(ObjectMapper objectMapper) {
    this.providers = loadProviders(objectMapper);
    this.providersByKey = providers.stream()
        .collect(Collectors.toUnmodifiableMap(ModelProviderDefinition::key, Function.identity()));
  }

  /**
   * 返回已配置的模型 Provider 目录。
   */

  public List<ModelProviderDefinition> listProviders() {
    return providers;
  }

  /**
   * 根据模型提供方标识查找模型提供方定义。
   */

  public ModelProviderDefinition findByKey(String key) {
    return providersByKey.get(trimString(key));
  }

  /**
   * 返回自定义 Provider 定义，缺失时视为配置错误。
   */

  public ModelProviderDefinition customProvider() {
    ModelProviderDefinition custom = findByKey("custom-provider");
    if (custom == null) {
      throw new IllegalStateException("model-providers.json must define custom-provider");
    }
    return custom;
  }

  private static List<ModelProviderDefinition> loadProviders(ObjectMapper objectMapper) {
    ClassPathResource resource = new ClassPathResource(PROVIDERS_RESOURCE);
    try (InputStream inputStream = resource.getInputStream()) {
      List<ModelProviderDefinition> definitions = objectMapper.readValue(
          inputStream,
          new TypeReference<>() {}
      );
      return List.copyOf(definitions);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to load " + PROVIDERS_RESOURCE, error);
    }
  }

  private static String trimString(String value) {
    return value == null ? "" : value.trim();
  }
}
