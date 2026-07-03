package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.model.NormalizedModelSelection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceFileServiceTest {

  @TempDir
  Path tempDir;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void writesOpenClawRuntimeConfigAndWorkspaceScaffold() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");

    InstanceFileService fileService = new InstanceFileService(properties, objectMapper);
    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Path instanceDir = tempDir.resolve("instances").resolve(draft.instance().getId());
    assertThat(instanceDir.resolve("README.txt")).exists();
    assertThat(instanceDir.resolve("workspace").resolve("MEMORY.md")).exists();
    assertThat(instanceDir.resolve("home").resolve(".openclaw").resolve("agents").resolve("main").resolve("agent").resolve("models.json"))
        .exists();

    Map<String, Object> config = objectMapper.readValue(
        instanceDir.resolve("home").resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    assertThat(config).containsKeys("gateway", "agents", "models", "plugins", "session");

    @SuppressWarnings("unchecked")
    Map<String, Object> gateway = (Map<String, Object>) config.get("gateway");
    assertThat(gateway).containsEntry("port", 18789);
    @SuppressWarnings("unchecked")
    Map<String, Object> controlUi = (Map<String, Object>) gateway.get("controlUi");
    assertThat(controlUi.get("allowedOrigins")).asList().contains("http://127.0.0.1:14300");

    @SuppressWarnings("unchecked")
    Map<String, Object> models = (Map<String, Object>) config.get("models");
    @SuppressWarnings("unchecked")
    Map<String, Object> providers = (Map<String, Object>) models.get("providers");
    @SuppressWarnings("unchecked")
    Map<String, Object> openai = (Map<String, Object>) providers.get("openai");
    assertThat(openai).containsEntry("api", "openai-responses");
    assertThat(openai).containsEntry("baseUrl", "https://example.com/v1");
    assertThat(openai).containsEntry("apiKey", "sk-test");
    assertThat(openai.get("models")).asList()
        .first()
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("contextWindow", 200_000)
        .containsEntry("maxTokens", 20_000);

    Map<String, Object> agentModels = objectMapper.readValue(
        instanceDir.resolve("home").resolve(".openclaw").resolve("agents").resolve("main").resolve("agent").resolve("models.json").toFile(),
        new TypeReference<>() {}
    );
    assertThat(agentModels.get("providers"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsKey("openai");

    @SuppressWarnings("unchecked")
    Map<String, Object> plugins = (Map<String, Object>) config.get("plugins");
    assertThat(plugins.get("allow"))
        .asList()
        .isEmpty();
    assertThat(plugins.get("entries"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .isEmpty();
    assertThat(config).doesNotContainKey("channels");

    @SuppressWarnings("unchecked")
    Map<String, Object> session = (Map<String, Object>) config.get("session");
    assertThat(session).containsEntry("dmScope", "per-account-channel-peer");

    assertThat(Files.readString(instanceDir.resolve("README.txt"))).contains("OpenClaw instance: OpenClaw");
  }

  @Test
  void preservesExistingChannelsWithoutForcingWechatChannel() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper);

    Path homeDir = tempDir.resolve("instances").resolve(draft.instance().getId()).resolve("home");
    Files.createDirectories(homeDir);
    objectMapper.writeValue(homeDir.resolve("openclaw.json").toFile(), Map.of(
        "channels", Map.of(
            "custom-channel", Map.of("enabled", true, "custom", "keep"),
            "openclaw-weixin", Map.of("enabled", false, "botAgent", "CustomBot/1.0")
        ),
        "session", Map.of("custom", "keep"),
        "meta", Map.of("source", "existing")
    ));

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Map<String, Object> config = objectMapper.readValue(
        homeDir.resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    @SuppressWarnings("unchecked")
    Map<String, Object> channels = (Map<String, Object>) config.get("channels");
    assertThat(channels.get("custom-channel"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("enabled", true)
        .containsEntry("custom", "keep");
    assertThat(channels.get("openclaw-weixin"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("enabled", false)
        .containsEntry("botAgent", "CustomBot/1.0");
    assertThat(config.get("session"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("custom", "keep")
        .containsEntry("dmScope", "per-account-channel-peer");
    assertThat(config.get("meta"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("source", "existing");
  }

  @Test
  void enablesWechatChannelWhenPluginIsAllowedOnInstance() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    draft.instance().setPluginsAllow("[\"openclaw-weixin\"]");
    draft.instance().setPluginsEntries("{\"openclaw-weixin\":{\"enabled\":true}}");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper);

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Path homeDir = tempDir.resolve("instances").resolve(draft.instance().getId()).resolve("home");
    Map<String, Object> config = objectMapper.readValue(
        homeDir.resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    @SuppressWarnings("unchecked")
    Map<String, Object> channels = (Map<String, Object>) config.get("channels");
    assertThat(channels.get("openclaw-weixin"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("enabled", true)
        .containsEntry("replyProgressMessages", true);
  }

  @Test
  void enablesApiChannelWhenPluginIsAllowedOnInstance() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    draft.instance().setPluginsAllow("[\"claw-manager-api\"]");
    draft.instance().setPluginsEntries("{\"claw-manager-api\":{\"enabled\":true}}");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper);

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Path homeDir = tempDir.resolve("instances").resolve(draft.instance().getId()).resolve("home");
    Map<String, Object> config = objectMapper.readValue(
        homeDir.resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    @SuppressWarnings("unchecked")
    Map<String, Object> channels = (Map<String, Object>) config.get("channels");
    assertThat(channels.get("claw-manager-api"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("enabled", true);
  }

  @Test
  void enablesOpenVikingContextEngineSlotWhenPluginIsAllowedOnInstance() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    draft.instance().setPluginsAllow("[\"openviking\"]");
    draft.instance().setPluginsEntries("{\"openviking\":{\"enabled\":true}}");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper);

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Path homeDir = tempDir.resolve("instances").resolve(draft.instance().getId()).resolve("home");
    Map<String, Object> config = objectMapper.readValue(
        homeDir.resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    @SuppressWarnings("unchecked")
    Map<String, Object> plugins = (Map<String, Object>) config.get("plugins");
    assertThat(plugins.get("slots"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("contextEngine", "openviking");
  }

  private ClawbotProperties testProperties() {
    return new ClawbotProperties(
        new ClawbotProperties.Paths(tempDir.toString()),
        new ClawbotProperties.Admin("", "平台管理员", ""),
        new ClawbotProperties.Security("clawbot_session", 14),
        new ClawbotProperties.Runtime(
            "runner:latest",
            600_000,
            "1.0",
            "1g",
            600_000,
            120_000,
            1_800_000,
            10_000,
            5_000,
            List.of("http://127.0.0.1:14300")
        )
    );
  }

  private InstanceCreationDraft createDraft(String name) {
    InstanceRecordFactory factory = new InstanceRecordFactory(objectMapper);
    return factory.create(name, new NormalizedModelSelection(
        "custom-provider",
        "openai",
        "gpt-5.5",
        "openai-responses",
        "custom_gateway",
        "openai",
        "",
        "https://example.com/v1",
        "sk-test",
        null,
        "{}",
        200_000,
        20_000
    ), "preset_1", 19001);
  }
}
