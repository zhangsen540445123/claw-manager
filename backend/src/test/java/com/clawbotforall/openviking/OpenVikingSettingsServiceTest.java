package com.clawbotforall.openviking;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.config.ClawbotProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenVikingSettingsServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void publicSettingsUseDefaultsAndDoNotExposeSecret() {
    FakeOpenVikingSettingsMapper mapper = new FakeOpenVikingSettingsMapper();
    OpenVikingSettingsService service = new OpenVikingSettingsService(
        mapper,
        new OpenVikingIdentityService(properties()),
        new OpenVikingBrokerTokenService(properties())
    );

    PublicOpenVikingSettings settings = service.publicSettings();

    assertThat(settings.baseUrl()).isBlank();
    assertThat(settings.trustedModeEnabled()).isTrue();
    assertThat(settings.accountId()).isEqualTo("claw-manager");
    assertThat(settings.pluginPackage()).isEqualTo("npm:@claw-manager/openviking-openclaw-plugin@2026.6.36");
    assertThat(settings.rootApiKeyConfigured()).isFalse();
    assertThat(settings.rootApiKeyFingerprint()).isBlank();
    assertThat(settings.saltConfigured()).isTrue();
    assertThat(settings.saltSource()).isEqualTo("generated");
    assertThat(settings.saltFingerprint()).hasSize(16);
  }

  @Test
  void updateSettingsNormalizesInputAndPersistsGlobalRow() {
    FakeOpenVikingSettingsMapper mapper = new FakeOpenVikingSettingsMapper();
    OpenVikingSettingsService service = new OpenVikingSettingsService(
        mapper,
        new OpenVikingIdentityService(properties()),
        new OpenVikingBrokerTokenService(properties())
    );

    PublicOpenVikingSettings settings = service.updateSettings(Map.of(
        "baseUrl", " http://openviking:1933/ ",
        "trustedModeEnabled", false,
        "accountId", " account-main ",
        "pluginPackage", " npm:@claw-manager/openviking-openclaw-plugin@2026.6.36 ",
        "rootApiKey", " ov-root-secret ",
        "identitySalt", " shared-salt "
    ));

    assertThat(settings.baseUrl()).isEqualTo("http://openviking:1933");
    assertThat(settings.trustedModeEnabled()).isFalse();
    assertThat(settings.accountId()).isEqualTo("account-main");
    assertThat(settings.pluginPackage()).isEqualTo("npm:@claw-manager/openviking-openclaw-plugin@2026.6.36");
    assertThat(settings.rootApiKeyConfigured()).isTrue();
    assertThat(settings.rootApiKeyFingerprint()).hasSize(16);
    assertThat(settings.saltConfigured()).isTrue();
    assertThat(settings.saltSource()).isEqualTo("configured");
    assertThat(mapper.saved.getRootApiKey()).isEqualTo("ov-root-secret");
    assertThat(mapper.saved.getIdentitySalt()).isEqualTo("shared-salt");
    assertThat(mapper.saved.getId()).isEqualTo("global");
  }

  @Test
  void updateSettingsKeepsExistingSaltWhenPayloadOmitsItOrLeavesItBlank() {
    FakeOpenVikingSettingsMapper mapper = new FakeOpenVikingSettingsMapper();
    OpenVikingSettingsEntity entity = new OpenVikingSettingsEntity();
    entity.setId("global");
    entity.setIdentitySalt("existing-salt");
    mapper.saved = entity;
    OpenVikingSettingsService service = new OpenVikingSettingsService(
        mapper,
        new OpenVikingIdentityService(properties()),
        new OpenVikingBrokerTokenService(properties())
    );

    PublicOpenVikingSettings omitted = service.updateSettings(Map.of("baseUrl", "http://openviking:1933"));
    PublicOpenVikingSettings blank = service.updateSettings(Map.of("identitySalt", "   "));

    assertThat(omitted.saltFingerprint()).isEqualTo(blank.saltFingerprint());
    assertThat(mapper.saved.getIdentitySalt()).isEqualTo("existing-salt");
  }

  @Test
  void updateSettingsKeepsExistingRootKeyWhenPayloadOmitsItOrLeavesItBlank() {
    FakeOpenVikingSettingsMapper mapper = new FakeOpenVikingSettingsMapper();
    OpenVikingSettingsEntity entity = new OpenVikingSettingsEntity();
    entity.setId("global");
    entity.setBaseUrl("http://openviking:1933");
    entity.setTrustedModeEnabled(false);
    entity.setAccountId("claw-manager");
    entity.setPluginPackage("npm:@claw-manager/openviking-openclaw-plugin@2026.6.36");
    entity.setRootApiKey("existing-root-key");
    mapper.saved = entity;
    OpenVikingSettingsService service = new OpenVikingSettingsService(
        mapper,
        new OpenVikingIdentityService(properties()),
        new OpenVikingBrokerTokenService(properties())
    );

    PublicOpenVikingSettings omitted = service.updateSettings(Map.of("baseUrl", "http://openviking:1933"));
    PublicOpenVikingSettings blank = service.updateSettings(Map.of("rootApiKey", "   "));

    assertThat(omitted.rootApiKeyConfigured()).isTrue();
    assertThat(blank.rootApiKeyConfigured()).isTrue();
    assertThat(mapper.saved.getRootApiKey()).isEqualTo("existing-root-key");
  }

  @Test
  void updateSettingsCanClearRootKeyExplicitly() {
    FakeOpenVikingSettingsMapper mapper = new FakeOpenVikingSettingsMapper();
    OpenVikingSettingsEntity entity = new OpenVikingSettingsEntity();
    entity.setId("global");
    entity.setRootApiKey("existing-root-key");
    mapper.saved = entity;
    OpenVikingSettingsService service = new OpenVikingSettingsService(
        mapper,
        new OpenVikingIdentityService(properties()),
        new OpenVikingBrokerTokenService(properties())
    );

    PublicOpenVikingSettings settings = service.updateSettings(Map.of("clearRootApiKey", true));

    assertThat(settings.rootApiKeyConfigured()).isFalse();
    assertThat(settings.rootApiKeyFingerprint()).isBlank();
    assertThat(mapper.saved.getRootApiKey()).isBlank();
  }

  @Test
  void effectiveSettingsIncludeRawSecretForRunnerEnvOnly() throws Exception {
    FakeOpenVikingSettingsMapper mapper = new FakeOpenVikingSettingsMapper();
    OpenVikingSettingsEntity entity = new OpenVikingSettingsEntity();
    entity.setId("global");
    entity.setBaseUrl("http://openviking:1933");
    entity.setTrustedModeEnabled(false);
    entity.setAccountId("claw-manager");
    entity.setPluginPackage("npm:@claw-manager/openviking-openclaw-plugin@2026.6.36");
    entity.setIdentitySalt("configured-salt");
    entity.setRootApiKey("root-key");
    mapper.saved = entity;
    OpenVikingSettingsService service = new OpenVikingSettingsService(
        mapper,
        new OpenVikingIdentityService(properties()),
        new OpenVikingBrokerTokenService(properties())
    );

    OpenVikingEffectiveSettings settings = service.effectiveSettings();

    assertThat(settings.baseUrl()).isEqualTo("http://openviking:1933");
    assertThat(settings.identityHashSecret()).isEqualTo("configured-salt");
    assertThat(settings.rootApiKey()).isEqualTo("root-key");
    assertThat(settings.brokerToken()).isNotBlank();
    assertThat(settings.internalBaseUrl()).isEqualTo("http://claw-manager-api:8080");
  }

  private ClawbotProperties properties() {
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
            1_000_000,
            128_000,
            List.of()
        )
    );
  }

  private static class FakeOpenVikingSettingsMapper implements OpenVikingSettingsMapper {
    OpenVikingSettingsEntity saved;

    @Override
    public OpenVikingSettingsEntity findGlobal() {
      return saved;
    }

    @Override
    public int upsert(OpenVikingSettingsEntity settings) {
      saved = settings;
      return 1;
    }
  }
}
