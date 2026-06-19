package com.clawbotforall.instance;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.web.RequestOrigins;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * 将实例持久化聚合转换为公共 API 响应。
 */
@Component
public class PublicInstanceFactory {

  private static final String WECHAT_CHANNEL_ID = "openclaw-weixin";

  private final ObjectMapper objectMapper;
  private final ClawbotProperties properties;

  public PublicInstanceFactory(ObjectMapper objectMapper, ClawbotProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public PublicInstance from(
      InstanceEntity instance,
      List<InstanceModelEntity> models,
      InstanceProvisioningEntity provisioning,
      InstanceModelAuthEntity modelAuth,
      InstanceWechatBindingEntity wechatBinding,
      List<WechatPairedAccountEntity> pairedAccounts,
      HttpServletRequest request
  ) {
    List<PublicInstanceModel> publicModels = models.stream()
        .map(this::publicModel)
        .toList();
    return new PublicInstance(
        instance.getId(),
        instance.getName(),
        instance.getSlug(),
        instance.getStatus(),
        instance.getPort(),
        dashboardUrl(instance, request),
        instance.getContainerName(),
        instance.getGatewayToken(),
        instance.getCreatedAt(),
        instance.getUpdatedAt(),
        publicProvisioning(instance, provisioning),
        publicModels.isEmpty() ? null : publicModels.getFirst(),
        publicModels,
        publicModelAuth(modelAuth),
        plugins(instance),
        publicWechatBinding(wechatBinding, pairedAccounts)
    );
  }

  private PublicInstanceModel publicModel(InstanceModelEntity model) {
    return new PublicInstanceModel(
        defaultString(model.getPresetId()),
        defaultString(model.getProviderKey()),
        defaultString(model.getProviderId()),
        defaultString(model.getModelId()),
        defaultString(model.getApiMode()),
        defaultString(model.getAuthType()),
        defaultString(model.getAuthProviderId()),
        defaultString(model.getAuthMethodId()),
        defaultString(model.getBaseUrl()),
        maskSecret(model.getApiKey()),
        readJsonMap(model.getExtra())
    );
  }

  private PublicInstanceProvisioning publicProvisioning(
      InstanceEntity instance,
      InstanceProvisioningEntity provisioning
  ) {
    if (provisioning == null) {
      return new PublicInstanceProvisioning(
          "ready",
          100,
          "ready",
          "实例已就绪。",
          null,
          instance.getUpdatedAt()
      );
    }
    return new PublicInstanceProvisioning(
        defaultString(provisioning.getStatus()),
        provisioning.getPercent(),
        defaultString(provisioning.getStage()),
        defaultString(provisioning.getMessage()),
        provisioning.getGatewayStartedAt(),
        provisioning.getUpdatedAt()
    );
  }

  private PublicInstanceModelAuth publicModelAuth(InstanceModelAuthEntity auth) {
    if (auth == null) {
      return new PublicInstanceModelAuth("idle", null, "", "", "", "", false);
    }
    return new PublicInstanceModelAuth(
        defaultString(auth.getStatus()),
        auth.getUpdatedAt(),
        defaultString(auth.getMessage()),
        defaultString(auth.getOutputSnippet()),
        defaultString(auth.getAuthUrl()),
        defaultString(auth.getPromptLabel()),
        auth.isNeedsInput()
    );
  }

  private PublicWechatBinding publicWechatBinding(
      InstanceWechatBindingEntity binding,
      List<WechatPairedAccountEntity> pairedAccounts
  ) {
    List<PublicWechatPairedAccount> accounts = pairedAccounts.stream()
        .map(account -> new PublicWechatPairedAccount(
            defaultString(account.getAccountId()),
            defaultString(account.getPhone()),
            defaultString(account.getWechatUserId()),
            defaultString(account.getRemark()),
            defaultString(account.getBaseUrl()),
            account.getSavedAt(),
            account.getBoundAt(),
            account.getUpdatedAt()
        ))
        .toList();

    if (binding == null) {
      return new PublicWechatBinding("idle", null, null, false, null, "", "", "", accounts, false, "idle", "", null);
    }

    String qrExpiresAt = binding.getQrExpiresAt();
    boolean qrExpired = isQrExpired(qrExpiresAt);
    String status = qrExpired && "waiting_scan".equals(binding.getStatus()) ? "expired" : defaultString(binding.getStatus());
    return new PublicWechatBinding(
        status,
        binding.getUpdatedAt(),
        qrExpiresAt,
        qrExpired,
        qrExpired ? null : binding.getQrMode(),
        qrExpired ? "" : defaultString(binding.getQrPayload()),
        qrExpired ? "" : defaultString(binding.getQrLink()),
        defaultString(binding.getOutputSnippet()),
        accounts,
        binding.isRuntimeReady(),
        defaultString(binding.getRuntimeStatus()).isBlank() ? "idle" : binding.getRuntimeStatus(),
        defaultString(binding.getRuntimeMessage()),
        binding.getRuntimeUpdatedAt()
    );
  }

  private boolean isQrExpired(String qrExpiresAt) {
    if (qrExpiresAt == null || qrExpiresAt.isBlank()) {
      return false;
    }
    try {
      return !Instant.parse(qrExpiresAt).isAfter(Instant.now());
    } catch (DateTimeParseException error) {
      return false;
    }
  }

  private Map<String, Object> plugins(InstanceEntity instance) {
    List<Object> allow = readJsonList(instance.getPluginsAllow());
    if (!allow.contains(WECHAT_CHANNEL_ID)) {
      allow = new ArrayList<>(allow);
      allow.add(WECHAT_CHANNEL_ID);
    }

    Map<String, Object> entries = readJsonMap(instance.getPluginsEntries());
    Object wechatEntry = entries.get(WECHAT_CHANNEL_ID);
    Map<String, Object> normalizedWechatEntry = wechatEntry instanceof Map<?, ?> map
        ? stringKeyMap(map)
        : new LinkedHashMap<>();
    normalizedWechatEntry.putIfAbsent("enabled", true);
    entries.put(WECHAT_CHANNEL_ID, normalizedWechatEntry);

    Map<String, Object> plugins = new LinkedHashMap<>();
    plugins.put("allow", allow);
    plugins.put("entries", entries);
    return plugins;
  }

  private String dashboardUrl(InstanceEntity instance, HttpServletRequest request) {
    String proxyPath = "/proxy/" + instance.getId();
    String tokenFragment = "#token="
        + UriUtils.encodeFragment(defaultString(instance.getGatewayToken()), StandardCharsets.UTF_8);
    if (request != null) {
      String origin = RequestOrigins.resolve(request);
      String gatewayUrl = origin.replaceFirst("^http", "ws") + proxyPath;
      return proxyPath + "/?gatewayUrl="
          + URLEncoder.encode(gatewayUrl, StandardCharsets.UTF_8)
          + tokenFragment;
    }
    return proxyPath + "/" + tokenFragment;
  }

  private Map<String, Object> readJsonMap(String rawJson) {
    Object value = readJson(rawJson);
    if (value instanceof Map<?, ?> map) {
      return stringKeyMap(map);
    }
    return new LinkedHashMap<>();
  }

  private List<Object> readJsonList(String rawJson) {
    Object value = readJson(rawJson);
    if (value instanceof List<?> list) {
      return new ArrayList<>(list);
    }
    return new ArrayList<>();
  }

  private Object readJson(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(rawJson, Object.class);
    } catch (JsonProcessingException error) {
      return null;
    }
  }

  private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return result;
  }

  private static String maskSecret(String secret) {
    String value = defaultString(secret);
    if (value.isEmpty()) {
      return "";
    }
    if (value.length() <= 8) {
      return value.substring(0, Math.min(2, value.length())) + "***";
    }
    return value.substring(0, 4) + "••••" + value.substring(value.length() - 4);
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
