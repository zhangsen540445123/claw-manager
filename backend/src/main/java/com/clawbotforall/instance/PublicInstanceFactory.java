package com.clawbotforall.instance;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.openviking.OpenVikingIdentityService;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.web.RequestOrigins;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

  private final ObjectMapper objectMapper;
  private final ClawbotProperties properties;
  private final OpenVikingSettingsService openVikingSettingsService;
  private final OpenVikingIdentityService openVikingIdentityService;

  public PublicInstanceFactory(
      ObjectMapper objectMapper,
      ClawbotProperties properties,
      OpenVikingSettingsService openVikingSettingsService,
      OpenVikingIdentityService openVikingIdentityService
  ) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.openVikingSettingsService = openVikingSettingsService;
    this.openVikingIdentityService = openVikingIdentityService;
  }

  public PublicInstance from(
      InstanceEntity instance,
      List<InstanceModelEntity> models,
      InstanceProvisioningEntity provisioning,
      InstanceModelAuthEntity modelAuth,
      List<WechatPairedAccountEntity> pairedAccounts,
      List<WechatAccountChannelEntity> accountChannels,
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
        publicWechatBinding(pairedAccounts, accountChannels)
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
        model.getContextWindow(),
        model.getMaxTokens(),
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
      List<WechatPairedAccountEntity> pairedAccounts,
      List<WechatAccountChannelEntity> accountChannels
  ) {
    Map<String, WechatAccountChannelEntity> channelByAccountId = accountChannels.stream()
        .collect(java.util.stream.Collectors.toMap(
            WechatAccountChannelEntity::getAccountId,
            item -> item,
            (left, right) -> left,
            LinkedHashMap::new
        ));
    String identitySalt = pairedAccounts.isEmpty()
        ? ""
        : openVikingSettingsService.effectiveSettings().identityHashSecret();
    List<PublicWechatPairedAccount> accounts = pairedAccounts.stream()
        .map(account -> {
          WechatAccountChannelEntity channel = channelByAccountId.get(account.getAccountId());
          return new PublicWechatPairedAccount(
              defaultString(account.getAccountId()),
              defaultString(account.getPhone()),
              defaultString(account.getWechatUserId()),
              openVikingUserId(account.getWechatUserId(), identitySalt),
              defaultString(account.getRemark()),
              defaultString(account.getBaseUrl()),
              account.getSavedAt(),
              account.getBoundAt(),
              account.getUpdatedAt(),
              channel == null ? "unknown" : defaultString(channel.getStatus()),
              channel == null ? "" : defaultString(channel.getMessage()),
              channel == null ? null : channel.getUpdatedAt(),
              channel == null ? null : channel.getLastStartedAt(),
              channel == null ? null : channel.getLastErrorAt()
          );
        })
        .toList();

    ChannelSummary summary = summarizeChannels(accountChannels, accounts.isEmpty());
    return new PublicWechatBinding(
        summary.status(),
        summary.updatedAt(),
        null,
        false,
        null,
        "",
        "",
        summary.message(),
        accounts,
        summary.runtimeReady(),
        summary.runtimeStatus(),
        summary.message(),
        summary.updatedAt()
    );
  }

  private ChannelSummary summarizeChannels(List<WechatAccountChannelEntity> channels, boolean noAccounts) {
    if (noAccounts) {
      return new ChannelSummary("idle", false, "idle", "", null);
    }
    WechatAccountChannelEntity latest = channels.stream()
        .filter(channel -> channel.getUpdatedAt() != null && !channel.getUpdatedAt().isBlank())
        .max((left, right) -> left.getUpdatedAt().compareTo(right.getUpdatedAt()))
        .orElse(null);
    String updatedAt = latest == null ? null : latest.getUpdatedAt();
    String message = latest == null ? "" : defaultString(latest.getMessage());
    boolean hasStarting = channels.stream().anyMatch(channel -> "starting".equals(defaultString(channel.getStatus())));
    if (hasStarting) {
      return new ChannelSummary("starting", false, "pending", message, updatedAt);
    }
    boolean hasReady = channels.stream().anyMatch(channel -> "ready".equals(defaultString(channel.getStatus())));
    if (hasReady) {
      return new ChannelSummary("ready", true, "ready", message, updatedAt);
    }
    boolean hasError = channels.stream().anyMatch(channel -> "error".equals(defaultString(channel.getStatus())));
    if (hasError) {
      return new ChannelSummary("error", false, "error", message, updatedAt);
    }
    return new ChannelSummary("unknown", false, "unknown", message, updatedAt);
  }

  private record ChannelSummary(
      String status,
      boolean runtimeReady,
      String runtimeStatus,
      String message,
      String updatedAt
  ) {}

  private Map<String, Object> plugins(InstanceEntity instance) {
    List<Object> allow = readJsonList(instance.getPluginsAllow());
    Map<String, Object> entries = readJsonMap(instance.getPluginsEntries());

    Map<String, Object> plugins = new LinkedHashMap<>();
    plugins.put("allow", allow);
    plugins.put("entries", entries);
    return plugins;
  }

  private String openVikingUserId(String wechatUserId, String identitySalt) {
    return openVikingIdentityService.resolveSenderIdentity(wechatUserId, identitySalt)
        .map(com.clawbotforall.openviking.OpenVikingSenderIdentity::openVikingUserId)
        .orElse("");
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
