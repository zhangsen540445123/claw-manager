package com.clawbotforall.instance;

import com.clawbotforall.model.NormalizedModelSelection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 创建标准化实例记录及相关子记录。
 */
@Component
public class InstanceRecordFactory {

  public static final int INSTANCE_BASE_PORT = 19000;

  private final ObjectMapper objectMapper;

  public InstanceRecordFactory(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * 使用默认子状态构建新的实例聚合。
   */

  public InstanceCreationDraft create(
      String name,
      NormalizedModelSelection model,
      String presetId,
      int port
  ) {
    String id = Long.toString(System.currentTimeMillis(), 36)
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    String now = Instant.now().toString();

    InstanceEntity instance = new InstanceEntity();
    instance.setId(id);
    instance.setName(name);
    String slug = slugify(name);
    instance.setSlug("instance".equals(slug) ? "instance-" + id.substring(Math.max(0, id.length() - 6)) : slug);
    instance.setStatus("stopped");
    instance.setPort(port);
    instance.setDashboardUrl("http://127.0.0.1:" + port + "/");
    instance.setContainerName("clawbot-openclaw-" + id);
    instance.setGatewayToken(id + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
    instance.setPluginsAllow(writeJson(new String[] {}));
    instance.setPluginsEntries(writeJson(Map.of()));
    instance.setCreatedAt(now);
    instance.setUpdatedAt(now);

    InstanceModelEntity instanceModel = new InstanceModelEntity();
    instanceModel.setInstanceId(id);
    instanceModel.setSortOrder(0);
    instanceModel.setPresetId(normalizedPresetId(presetId));
    instanceModel.setProviderKey(model.providerKey());
    instanceModel.setProviderId(model.providerId());
    instanceModel.setModelId(model.modelId());
    instanceModel.setApiMode(model.apiMode());
    instanceModel.setAuthType(model.authType());
    instanceModel.setAuthProviderId(model.authProviderId());
    instanceModel.setAuthMethodId(model.authMethodId());
    instanceModel.setBaseUrl(model.baseUrl());
    instanceModel.setApiKey(model.apiKey());
    instanceModel.setProviderConfig(model.providerConfigJson());
    instanceModel.setExtra(model.extraJson());

    InstanceProvisioningEntity provisioning = new InstanceProvisioningEntity();
    provisioning.setInstanceId(id);
    provisioning.setStatus("running");
    provisioning.setPercent(5);
    provisioning.setStage("queued");
    provisioning.setMessage("正在创建实例目录与默认配置。");
    provisioning.setGatewayStartedAt(null);
    provisioning.setUpdatedAt(now);

    InstanceModelAuthEntity modelAuth = new InstanceModelAuthEntity();
    modelAuth.setInstanceId(id);
    modelAuth.setStatus("idle");
    modelAuth.setMessage("");
    modelAuth.setOutputSnippet("");
    modelAuth.setAuthUrl("");
    modelAuth.setPromptLabel("");
    modelAuth.setNeedsInput(false);
    modelAuth.setUpdatedAt(null);

    return new InstanceCreationDraft(instance, instanceModel, provisioning, modelAuth);
  }

  /**
   * 规范化实例名称并限制长度。
   */

  public String sanitizeName(Object value) {
    String normalized = value == null ? "" : String.valueOf(value).trim().replaceAll("\\s+", " ");
    return normalized.substring(0, Math.min(60, normalized.length()));
  }

  private String slugify(String input) {
    String slug = input == null ? "" : input.trim().toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "");
    if (slug.length() > 40) {
      slug = slug.substring(0, 40);
    }
    return slug.isBlank() ? "instance" : slug;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("实例默认 JSON 配置序列化失败。", error);
    }
  }

  private static String normalizedPresetId(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
