package com.clawbotforall.instance;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 为实例数据目录写入和维护 OpenClaw 配置文件。
 */
@Service
public class InstanceFileService {

  private static final int GATEWAY_PORT = 18789;
  private static final String WECHAT_CHANNEL_ID = "openclaw-weixin";
  private static final String API_CHANNEL_ID = "claw-manager-api";
  private static final String OPENVIKING_PLUGIN_ID = "openviking";
  private static final String CONTROL_UI_ROOT = "/usr/local/lib/node_modules/openclaw/dist/control-ui";

  private final ClawbotProperties properties;
  private final ObjectMapper objectMapper;

  public InstanceFileService(
      ClawbotProperties properties,
      ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
  }

  /**
   * 写入实例的 OpenClaw 配置文件。
   */

  public InstancePaths writeInstanceFiles(
      InstanceEntity instance,
      List<InstanceModelEntity> models
  ) {
    InstancePaths paths = paths(instance.getId());
    try {
      ensureLayout(paths);
      Map<String, Object> providers = mergeProviderConfigs(models);
      Map<String, Object> config = buildOpenClawConfig(instance, models, providers, readExistingConfig(paths));
      objectMapper.writeValue(paths.homeDir().resolve("openclaw.json").toFile(), config);
      writeAgentModels(paths, providers);
      writeReadme(instance, paths);
      return paths;
    } catch (IOException error) {
      throw new IllegalStateException("写入 OpenClaw 实例配置失败。", error);
    }
  }

  /**
   * 返回实例拥有的文件系统路径。
   */

  public InstancePaths paths(String instanceId) {
    Path baseDir = Path.of(properties.paths().dataDir(), "instances", instanceId);
    return new InstancePaths(
        baseDir,
        baseDir.resolve("home"),
        baseDir.resolve("workspace"),
        baseDir.resolve("logs")
    );
  }

  private void ensureLayout(InstancePaths paths) throws IOException {
    Files.createDirectories(paths.baseDir());
    Files.createDirectories(paths.homeDir());
    Files.createDirectories(paths.workspaceDir());
    Files.createDirectories(paths.logsDir());

    Path memoryDir = paths.workspaceDir().resolve("memory");
    Files.createDirectories(memoryDir);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<Path> memoryFiles = List.of(
        paths.workspaceDir().resolve("MEMORY.md"),
        memoryDir.resolve(today + ".md"),
        memoryDir.resolve(today.minusDays(1) + ".md")
    );
    for (Path file : memoryFiles) {
      if (!Files.exists(file)) {
        Files.writeString(file, "", StandardCharsets.UTF_8);
      }
    }
  }

  private Map<String, Object> buildOpenClawConfig(
      InstanceEntity instance,
      List<InstanceModelEntity> models,
      Map<String, Object> providers,
      Map<String, Object> existingConfig
  ) {
    Map<String, Object> managed = new LinkedHashMap<>();
    managed.put("gateway", gatewayConfig(instance));
    managed.put("agents", agentsConfig(models));

    if (!providers.isEmpty()) {
      managed.put("models", Map.of(
          "mode", "merge",
          "providers", providers
      ));
    }

    managed.put("plugins", pluginsConfig(instance));
    Map<String, Object> channels = channelsConfig(instance);
    if (!channels.isEmpty()) {
      managed.put("channels", channels);
    }
    managed.put("session", sessionConfig());
    return mergeOpenClawRuntimeConfig(existingConfig, managed);
  }

  private Map<String, Object> gatewayConfig(InstanceEntity instance) {
    Map<String, Object> auth = Map.of(
        "mode", "token",
        "token", instance.getGatewayToken()
    );
    Map<String, Object> controlUi = new LinkedHashMap<>();
    controlUi.put("enabled", true);
    controlUi.put("root", CONTROL_UI_ROOT);
    controlUi.put("allowInsecureAuth", true);
    controlUi.put("dangerouslyDisableDeviceAuth", true);
    controlUi.put("dangerouslyAllowHostHeaderOriginFallback", true);
    List<String> allowedOrigins = properties.runtime().controlUiAllowedOrigins().stream()
        .map(String::trim)
        .filter(origin -> !origin.isBlank())
        .distinct()
        .toList();
    if (!allowedOrigins.isEmpty()) {
      controlUi.put("allowedOrigins", allowedOrigins);
    }

    Map<String, Object> gateway = new LinkedHashMap<>();
    gateway.put("mode", "local");
    gateway.put("bind", "lan");
    gateway.put("port", GATEWAY_PORT);
    gateway.put("auth", auth);
    gateway.put("controlUi", controlUi);
    return gateway;
  }

  private Map<String, Object> agentsConfig(List<InstanceModelEntity> models) {
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("workspace", "/workspace");
    if (!models.isEmpty()) {
      InstanceModelEntity primary = models.getFirst();
      Map<String, Object> model = new LinkedHashMap<>();
      model.put("primary", primary.getProviderId() + "/" + primary.getModelId());
      List<String> fallbacks = models.stream()
          .skip(1)
          .map(item -> item.getProviderId() + "/" + item.getModelId())
          .toList();
      if (!fallbacks.isEmpty()) {
        model.put("fallbacks", fallbacks);
      }
      defaults.put("model", model);
    }
    return Map.of("defaults", defaults);
  }

  private Map<String, Object> mergeProviderConfigs(List<InstanceModelEntity> models) {
    Map<String, Object> providers = new LinkedHashMap<>();
    for (InstanceModelEntity model : models) {
      String providerId = defaultString(model.getProviderId()).trim();
      if (providerId.isBlank()) {
        continue;
      }

      Map<String, Object> providerConfig = providerConfig(model);
      if (!providers.containsKey(providerId)) {
        providers.put(providerId, providerConfig);
        continue;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> target = (Map<String, Object>) providers.get(providerId);
      mergeProviderConfig(target, providerConfig);
    }
    return providers;
  }

  private Map<String, Object> providerConfig(InstanceModelEntity model) {
    Map<String, Object> savedConfig = readJsonMap(model.getProviderConfig());
    if (!savedConfig.isEmpty()) {
      return savedConfig;
    }

    Map<String, Object> config = new LinkedHashMap<>();
    config.put("api", defaultString(model.getApiMode()));
    if (!defaultString(model.getBaseUrl()).isBlank()) {
      config.put("baseUrl", model.getBaseUrl());
    }
    if (!defaultString(model.getApiKey()).isBlank()) {
      config.put("apiKey", model.getApiKey());
    }
    config.put("models", List.of(modelDefinition(model.getModelId())));
    return config;
  }

  private void mergeProviderConfig(Map<String, Object> target, Map<String, Object> patch) {
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      if ("models".equals(entry.getKey())) {
        continue;
      }
      Object current = target.get(entry.getKey());
      if ((current == null || String.valueOf(current).isBlank()) && entry.getValue() != null) {
        target.put(entry.getKey(), entry.getValue());
      }
    }

    List<Map<String, Object>> targetModels = readModelList(target.get("models"));
    LinkedHashSet<String> existingIds = new LinkedHashSet<>();
    for (Map<String, Object> model : targetModels) {
      existingIds.add(defaultString(model.get("id")));
    }
    for (Map<String, Object> model : readModelList(patch.get("models"))) {
      String id = defaultString(model.get("id"));
      if (!id.isBlank() && existingIds.add(id)) {
        targetModels.add(model);
      }
    }
    target.put("models", targetModels);
  }

  private Map<String, Object> modelDefinition(String modelId) {
    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("id", defaultString(modelId));
    definition.put("name", defaultString(modelId));
    definition.put("reasoning", true);
    definition.put("input", List.of("text", "image"));
    definition.put("cost", Map.of(
        "input", 0,
        "output", 0,
        "cacheRead", 0,
        "cacheWrite", 0
    ));
    definition.put("contextWindow", properties.runtime().modelContextWindow());
    definition.put("maxTokens", properties.runtime().modelMaxTokens());
    return definition;
  }

  private void writeAgentModels(
      InstancePaths paths,
      Map<String, Object> providers
  ) throws IOException {
    Path agentDir = paths.homeDir()
        .resolve(".openclaw")
        .resolve("agents")
        .resolve("main")
        .resolve("agent");
    Files.createDirectories(agentDir);
    objectMapper.writeValue(agentDir.resolve("models.json").toFile(), Map.of("providers", providers));
  }

  private Map<String, Object> pluginsConfig(InstanceEntity instance) {
    List<Object> allow = readJsonList(instance.getPluginsAllow());
    Map<String, Object> entries = readJsonMap(instance.getPluginsEntries());

    Map<String, Object> plugins = new LinkedHashMap<>();
    plugins.put("allow", allow);
    plugins.put("entries", entries);
    if (allow.contains(OPENVIKING_PLUGIN_ID)) {
      plugins.put("slots", Map.of("contextEngine", OPENVIKING_PLUGIN_ID));
    }
    return plugins;
  }

  private Map<String, Object> channelsConfig(InstanceEntity instance) {
    List<Object> allow = readJsonList(instance.getPluginsAllow());
    Map<String, Object> channels = new LinkedHashMap<>();
    if (allow.contains(WECHAT_CHANNEL_ID)) {
      Map<String, Object> wechatChannel = new LinkedHashMap<>();
      wechatChannel.put("enabled", true);
      wechatChannel.put("replyProgressMessages", true);
      channels.put(WECHAT_CHANNEL_ID, wechatChannel);
    }
    if (allow.contains(API_CHANNEL_ID)) {
      channels.put(API_CHANNEL_ID, Map.of("enabled", true));
    }
    return channels;
  }

  private Map<String, Object> sessionConfig() {
    Map<String, Object> session = new LinkedHashMap<>();
    session.put("dmScope", "per-account-channel-peer");
    return session;
  }

  private Map<String, Object> mergeOpenClawRuntimeConfig(
      Map<String, Object> existingConfig,
      Map<String, Object> managedConfig
  ) {
    if (existingConfig == null || existingConfig.isEmpty()) {
      return managedConfig;
    }

    Map<String, Object> result = new LinkedHashMap<>(existingConfig);
    result.putAll(managedConfig);
    Map<String, Object> channels = mergeNestedObjectConfig(
        existingConfig.get("channels"),
        managedConfig.get("channels")
    );
    if (!channels.isEmpty()) {
      result.put("channels", channels);
    }
    Map<String, Object> session = mergeNestedObjectConfig(
        existingConfig.get("session"),
        managedConfig.get("session")
    );
    if (!session.isEmpty()) {
      result.put("session", session);
    }
    if (existingConfig.get("meta") instanceof Map<?, ?> meta) {
      result.put("meta", stringKeyMap(meta));
    }
    return result;
  }

  private Map<String, Object> mergeNestedObjectConfig(Object existingValue, Object managedValue) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (existingValue instanceof Map<?, ?> existingMap) {
      result.putAll(stringKeyMap(existingMap));
    }
    if (!(managedValue instanceof Map<?, ?> managedMap)) {
      return result;
    }

    for (Map.Entry<String, Object> entry : stringKeyMap(managedMap).entrySet()) {
      Object current = result.get(entry.getKey());
      if (current instanceof Map<?, ?> currentMap && entry.getValue() instanceof Map<?, ?> patchMap) {
        Map<String, Object> merged = new LinkedHashMap<>(stringKeyMap(currentMap));
        merged.putAll(stringKeyMap(patchMap));
        result.put(entry.getKey(), merged);
      } else {
        result.put(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }

  private Map<String, Object> readExistingConfig(InstancePaths paths) {
    Path configPath = paths.homeDir().resolve("openclaw.json");
    if (!Files.exists(configPath)) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(configPath.toFile(), new TypeReference<>() {});
    } catch (IOException ignored) {
      return new LinkedHashMap<>();
    }
  }

  private void writeReadme(InstanceEntity instance, InstancePaths paths) throws IOException {
    List<String> lines = List.of(
        "OpenClaw instance: " + instance.getName(),
        "Updated at: " + instance.getUpdatedAt(),
        "Home: " + paths.homeDir(),
        "Workspace: " + paths.workspaceDir(),
        "Dashboard: " + instance.getDashboardUrl()
    );
    Files.writeString(paths.baseDir().resolve("README.txt"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }

  private Map<String, Object> readJsonMap(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(rawJson, new TypeReference<>() {});
    } catch (JsonProcessingException error) {
      return new LinkedHashMap<>();
    }
  }

  private List<Object> readJsonList(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return new ArrayList<>();
    }
    try {
      return objectMapper.readValue(rawJson, new TypeReference<>() {});
    } catch (JsonProcessingException error) {
      return new ArrayList<>();
    }
  }

  private List<Map<String, Object>> readModelList(Object value) {
    if (!(value instanceof List<?> list)) {
      return new ArrayList<>();
    }
    List<Map<String, Object>> models = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        models.add(stringKeyMap(map));
      }
    }
    return models;
  }

  private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return result;
  }

  private static String defaultString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
