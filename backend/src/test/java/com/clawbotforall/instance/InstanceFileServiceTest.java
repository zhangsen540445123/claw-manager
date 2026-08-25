package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.image.ImageGenerationSettings;
import com.clawbotforall.image.ImageGenerationSettingsProvider;
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

    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());
    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Path instanceDir = tempDir.resolve("instances").resolve(draft.instance().getId());
    assertThat(instanceDir.resolve("README.txt")).exists();
    assertThat(instanceDir.resolve("workspace").resolve("MEMORY.md")).doesNotExist();
    assertThat(instanceDir.resolve("workspace").resolve("memory")).doesNotExist();
    assertThat(instanceDir.resolve("home").resolve(".openclaw").resolve("agents").resolve("main").resolve("agent").resolve("models.json"))
        .exists();
    assertThat(instanceDir.resolve("home").resolve(".openclaw").resolve("claw-manager").resolve("workspace-preset.json"))
        .exists();

    Map<String, Object> config = objectMapper.readValue(
        instanceDir.resolve("home").resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    assertThat(config).containsKeys("gateway", "agents", "models", "skills", "plugins", "session");

    @SuppressWarnings("unchecked")
    Map<String, Object> agents = (Map<String, Object>) config.get("agents");
    @SuppressWarnings("unchecked")
    Map<String, Object> defaults = (Map<String, Object>) agents.get("defaults");
    assertThat(defaults).containsEntry("workspace", "/workspace");
    assertThat(defaults).containsEntry("skipBootstrap", true);
    assertThat(defaults.get("compaction"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("memoryFlush")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("enabled", false);

    @SuppressWarnings("unchecked")
    Map<String, Object> gateway = (Map<String, Object>) config.get("gateway");
    assertThat(gateway).containsEntry("port", 18789);
    @SuppressWarnings("unchecked")
    Map<String, Object> controlUi = (Map<String, Object>) gateway.get("controlUi");
    assertThat(controlUi.get("allowedOrigins")).asList().containsExactly("*");

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

    assertThat(config.get("skills"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("load")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("watch", true)
        .extractingByKey("extraDirs")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .containsExactly("/workspace/skills");

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
    assertThat(plugins.get("slots"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("memory", "none");
    assertThat(config).doesNotContainKey("channels");

    @SuppressWarnings("unchecked")
    Map<String, Object> session = (Map<String, Object>) config.get("session");
    assertThat(session).containsEntry("dmScope", "per-account-channel-peer");

    assertThat(Files.readString(instanceDir.resolve("README.txt"))).contains("OpenClaw instance: OpenClaw");
  }

  @Test
  void doesNotWriteUnsupportedImageGenerationConfig() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    ImageGenerationSettings imageSettings = new ImageGenerationSettings(
        true,
        "openai",
        "gpt-image-2",
        "openai-images",
        "https://api.openai.com/v1",
        "sk-image",
        "{}",
        180_000,
        "2026-07-13T00:00:00Z"
    );
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, () -> imageSettings);

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Path configPath = tempDir.resolve("instances").resolve(draft.instance().getId())
        .resolve("home").resolve("openclaw.json");
    Map<String, Object> config = objectMapper.readValue(configPath.toFile(), new TypeReference<>() {});
    Map<String, Object> agents = (Map<String, Object>) config.get("agents");
    Map<String, Object> defaults = (Map<String, Object>) agents.get("defaults");
    assertThat(defaults).doesNotContainKey("imageGenerationModel");
    Map<String, Object> models = (Map<String, Object>) config.get("models");
    Map<String, Object> providers = (Map<String, Object>) models.get("providers");
    assertThat(providers.get("openai"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("api", "openai-responses")
        .containsEntry("apiKey", "sk-test");
    assertThat(providers).doesNotContainKey("openai-image-generation");
  }

  @Test
  void preservesExistingChannelsWithoutForcingWechatChannel() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());

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
  void mergesSharedSkillExtraDirWithExistingSkillsConfig() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());
    Path homeDir = tempDir.resolve("instances").resolve(draft.instance().getId()).resolve("home");
    Files.createDirectories(homeDir);
    objectMapper.writeValue(homeDir.resolve("openclaw.json").toFile(), Map.of(
        "skills", Map.of(
            "allowBundled", List.of("github"),
            "load", Map.of(
                "extraDirs", List.of("/custom/skills", "/workspace/skills"),
                "watch", false,
                "watchDebounceMs", 500
            ),
            "entries", Map.of("custom-skill", Map.of("enabled", false))
        )
    ));

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Map<String, Object> config = objectMapper.readValue(
        homeDir.resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    assertThat(config.get("skills"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsKey("allowBundled")
        .containsKey("entries")
        .extractingByKey("load")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("watch", false)
        .containsEntry("watchDebounceMs", 500)
        .extractingByKey("extraDirs")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .containsExactly("/custom/skills", "/workspace/skills");
  }

  @Test
  void keepsSharedSkillExtraDirWhenInstanceFilesAreRewritten() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));
    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Path homeDir = tempDir.resolve("instances").resolve(draft.instance().getId()).resolve("home");
    Map<String, Object> config = objectMapper.readValue(
        homeDir.resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    assertThat(config.get("skills"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("load")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("extraDirs")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .containsExactly("/workspace/skills");
  }

  @Test
  void preservesDynamicUserAgentsAndBindingsWhenRewritingManagedConfig() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());
    String agentId = "user_8c2c63a5f96d294f03a9dbd4d7173348";
    Path homeDir = tempDir.resolve("instances").resolve(draft.instance().getId()).resolve("home");
    Files.createDirectories(homeDir);
    objectMapper.writeValue(homeDir.resolve("openclaw.json").toFile(), Map.of(
        "agents", Map.of(
            "defaults", Map.of("workspace", "/old-workspace"),
            "list", List.of(Map.of("id", agentId, "workspace", "/users/" + agentId))
        ),
        "bindings", List.of(Map.of(
            "agentId", agentId,
            "match", Map.of(
                "channel", "claw-manager-api",
                "accountId", "global",
                "peer", Map.of("kind", "direct", "id", "api:sender")
            )
        ))
    ));

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Map<String, Object> config = objectMapper.readValue(
        homeDir.resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    assertThat(config.get("agents"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("list")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .singleElement()
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("id", agentId);
    assertThat(config.get("agents"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("defaults")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("workspace", "/workspace")
        .containsEntry("skipBootstrap", true);
    assertThat(config.get("bindings"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .hasSize(1);
  }

  @Test
  void doesNotWriteAgentSkillAllowlistsForSharedSkillDirectory() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());
    String agentId = "user_8c2c63a5f96d294f03a9dbd4d7173348";
    Path homeDir = tempDir.resolve("instances").resolve(draft.instance().getId()).resolve("home");
    Files.createDirectories(homeDir);
    objectMapper.writeValue(homeDir.resolve("openclaw.json").toFile(), Map.of(
        "agents", Map.of(
            "list", List.of(Map.of(
                "id", agentId,
                "workspace", "/var/lib/openclaw/.openclaw/workspace-" + agentId,
                "agentDir", "/var/lib/openclaw/.openclaw/agents/" + agentId + "/agent"
            ))
        )
    ));

    fileService.writeInstanceFiles(draft.instance(), List.of(draft.model()));

    Map<String, Object> config = objectMapper.readValue(
        homeDir.resolve("openclaw.json").toFile(),
        new TypeReference<>() {}
    );
    assertThat(config.get("agents"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("defaults")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .doesNotContainKey("skills");
    assertThat(config.get("agents"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("list")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .singleElement()
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .doesNotContainKey("skills");
  }

  @Test
  void enablesWechatChannelWhenPluginIsAllowedOnInstance() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    draft.instance().setPluginsAllow("[\"openclaw-weixin\"]");
    draft.instance().setPluginsEntries("{\"openclaw-weixin\":{\"enabled\":true}}");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());

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
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());

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
        .containsEntry("enabled", true)
        .containsEntry("queueMonitor", true);
  }

  @Test
  void enablesOpenVikingContextEngineSlotWhenPluginIsAllowedOnInstance() throws Exception {
    ClawbotProperties properties = testProperties();
    InstanceCreationDraft draft = createDraft("OpenClaw");
    draft.instance().setPluginsAllow("[\"openviking\"]");
    draft.instance().setPluginsEntries("{\"openviking\":{\"enabled\":true}}");
    InstanceFileService fileService = new InstanceFileService(properties, objectMapper, ImageGenerationSettingsProvider.disabled());

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
        .containsEntry("contextEngine", "openviking")
        .containsEntry("memory", "none");
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
            List.of("*")
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
