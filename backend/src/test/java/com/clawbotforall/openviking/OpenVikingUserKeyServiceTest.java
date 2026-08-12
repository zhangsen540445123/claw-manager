package com.clawbotforall.openviking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.web.ApiException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenVikingUserKeyServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void firstResolveRegistersUserAndCachesUserKey() {
    FakeUserKeyMapper userKeys = new FakeUserKeyMapper();
    FakeAdminClient adminClient = new FakeAdminClient("user-key-a");
    OpenVikingUserKeyService service = service(userKeys, adminClient, "root-key");

    OpenVikingResolvedUserKey resolved = service.resolve(new OpenVikingUserResolveRequest(" wxid_Alpha ", ""));

    assertThat(resolved.accountId()).isEqualTo("claw-manager");
    assertThat(resolved.openvikingUserId()).startsWith("wx_");
    assertThat(resolved.userKey()).isEqualTo("user-key-a");
    assertThat(resolved.created()).isTrue();
    assertThat(adminClient.registerCalls).isEqualTo(1);
    assertThat(userKeys.rows).containsKey("claw-manager:" + resolved.openvikingUserId());
  }

  @Test
  void secondResolveReusesCachedUserKeyWithoutRegenerating() {
    FakeUserKeyMapper userKeys = new FakeUserKeyMapper();
    FakeAdminClient adminClient = new FakeAdminClient("user-key-a");
    OpenVikingUserKeyService service = service(userKeys, adminClient, "root-key");

    OpenVikingResolvedUserKey first = service.resolve(new OpenVikingUserResolveRequest("wxid_Alpha", ""));
    OpenVikingResolvedUserKey second = service.resolve(new OpenVikingUserResolveRequest("wxid_Alpha", ""));

    assertThat(second.openvikingUserId()).isEqualTo(first.openvikingUserId());
    assertThat(second.userKey()).isEqualTo("user-key-a");
    assertThat(second.created()).isFalse();
    assertThat(adminClient.registerCalls).isEqualTo(1);
    assertThat(adminClient.regenerateCalls).isZero();
  }

  @Test
  void resolveCanUsePreDerivedOpenVikingUserId() {
    FakeAdminClient adminClient = new FakeAdminClient("user-key-a");
    OpenVikingUserKeyService service = service(new FakeUserKeyMapper(), adminClient, "root-key");

    OpenVikingResolvedUserKey resolved = service.resolve(new OpenVikingUserResolveRequest("", "wx_0123456789abcdef0123456789abcdef"));

    assertThat(resolved.openvikingUserId()).isEqualTo("wx_0123456789abcdef0123456789abcdef");
    assertThat(adminClient.lastUserId).isEqualTo("wx_0123456789abcdef0123456789abcdef");
  }

  @Test
  void resolveCanUsePreDerivedApiOpenVikingUserId() {
    FakeAdminClient adminClient = new FakeAdminClient("user-key-api");
    OpenVikingUserKeyService service = service(new FakeUserKeyMapper(), adminClient, "root-key");

    OpenVikingResolvedUserKey resolved = service.resolve(new OpenVikingUserResolveRequest("", "api_0123456789abcdef0123456789abcdef"));

    assertThat(resolved.openvikingUserId()).isEqualTo("api_0123456789abcdef0123456789abcdef");
    assertThat(resolved.userKey()).isEqualTo("user-key-api");
    assertThat(adminClient.lastUserId).isEqualTo("api_0123456789abcdef0123456789abcdef");
  }

  @Test
  void rotateUserKeyDeletesLocalCacheAndRegeneratesRemoteKey() {
    FakeUserKeyMapper mapper = new FakeUserKeyMapper();
    FakeAdminClient adminClient = new FakeAdminClient("rotated-key");
    OpenVikingUserKeyEntity cached = new OpenVikingUserKeyEntity();
    cached.setAccountId("claw-manager");
    cached.setOpenvikingUserId("wx_0123456789abcdef0123456789abcdef");
    cached.setUserKey("old-key");
    mapper.upsert(cached);
    OpenVikingUserKeyService service = service(mapper, adminClient, "root-key");

    OpenVikingResolvedUserKey result = service.rotateUserKey("wx_0123456789abcdef0123456789abcdef");

    assertThat(result.userKey()).isEqualTo("rotated-key");
    assertThat(result.created()).isTrue();
    assertThat(adminClient.regenerateCalls).isEqualTo(1);
    assertThat(adminClient.registerCalls).isZero();
    assertThat(mapper.find("claw-manager", result.openvikingUserId()).getUserKey()).isEqualTo("rotated-key");
  }

  @Test
  void resolveRejectsMissingIdentity() {
    OpenVikingUserKeyService service = service(new FakeUserKeyMapper(), new FakeAdminClient("user-key-a"), "root-key");

    assertThatThrownBy(() -> service.resolve(new OpenVikingUserResolveRequest("  ", "")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("OpenViking user identity");
  }

  @Test
  void resolveRejectsMissingRootKey() {
    OpenVikingUserKeyService service = service(new FakeUserKeyMapper(), new FakeAdminClient("user-key-a"), "");

    assertThatThrownBy(() -> service.resolve(new OpenVikingUserResolveRequest("wxid_Alpha", "")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Root API Key");
  }

  private OpenVikingUserKeyService service(FakeUserKeyMapper userKeys, FakeAdminClient adminClient, String rootKey) {
    return new OpenVikingUserKeyService(
        userKeys,
        new StaticSettingsService(rootKey),
        new OpenVikingIdentityService(),
        adminClient
    );
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
            List.of()
        )
    );
  }

  private static class FakeUserKeyMapper implements OpenVikingUserKeyMapper {
    final Map<String, OpenVikingUserKeyEntity> rows = new HashMap<>();

    @Override
    public OpenVikingUserKeyEntity find(String accountId, String openvikingUserId) {
      return rows.get(accountId + ":" + openvikingUserId);
    }

    @Override
    public int upsert(OpenVikingUserKeyEntity userKey) {
      rows.put(userKey.getAccountId() + ":" + userKey.getOpenvikingUserId(), userKey);
      return 1;
    }

    @Override
    public int delete(String accountId, String openvikingUserId) {
      return rows.remove(accountId + ":" + openvikingUserId) == null ? 0 : 1;
    }

    @Override
    public int deleteByOpenvikingUserId(String openvikingUserId) {
      int before = rows.size();
      rows.entrySet().removeIf(entry -> entry.getValue().getOpenvikingUserId().equals(openvikingUserId));
      return before - rows.size();
    }
  }

  private static class FakeAdminClient implements OpenVikingAdminClient {
    final String userKey;
    int registerCalls;
    int regenerateCalls;
    String lastUserId;

    FakeAdminClient(String userKey) {
      this.userKey = userKey;
    }

    @Override
    public String registerUser(String baseUrl, String rootApiKey, String accountId, String openvikingUserId) {
      registerCalls += 1;
      lastUserId = openvikingUserId;
      return userKey;
    }

    @Override
    public String regenerateUserKey(String baseUrl, String rootApiKey, String accountId, String openvikingUserId) {
      regenerateCalls += 1;
      return userKey;
    }
  }

  private static class StaticSettingsService extends OpenVikingSettingsService {
    private final String rootKey;

    StaticSettingsService(String rootKey) {
      super(null, null);
      this.rootKey = rootKey;
    }

    @Override
    public OpenVikingEffectiveSettings effectiveSettings() {
      return new OpenVikingEffectiveSettings(
          "http://openviking:1933",
          false,
          "claw-manager",
          "secret",
          "npm:@claw-manager/openviking-openclaw-plugin@2026.6.37",
          rootKey,
          "broker-token",
          "http://claw-manager-api:8080"
      );
    }
  }
}
