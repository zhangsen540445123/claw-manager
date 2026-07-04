package com.clawbotforall.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class MiniappClientAdminServiceTest {

  @Mock
  MiniappClientMapper mapper;

  MiniappClientAdminService service;

  @BeforeEach
  void setUp() {
    service = new MiniappClientAdminService(
        mapper,
        Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC),
        () -> "cm_sk_generated_secret"
    );
  }

  @Test
  void listsClientsWithoutPlainSecret() {
    MiniappClientEntity client = client("miniapp_main", "cm_sk_plain_secret", true);
    when(mapper.list()).thenReturn(List.of(client));

    List<PublicMiniappClient> clients = service.listClients();

    assertThat(clients).hasSize(1);
    assertThat(clients.getFirst().appId()).isEqualTo("miniapp_main");
    assertThat(clients.getFirst().appSecret()).isNull();
    assertThat(clients.getFirst().appSecretPreview()).isEqualTo("cm_sk_plain_...cret");
    assertThat(clients.getFirst().enabled()).isTrue();
  }

  @Test
  void createsClientAndReturnsPlainSecretOnce() {
    PublicMiniappClient result = service.createClient(" miniapp_main ", true);

    ArgumentCaptor<MiniappClientEntity> captor = ArgumentCaptor.forClass(MiniappClientEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getAppId()).isEqualTo("miniapp_main");
    assertThat(captor.getValue().getAppSecret()).isEqualTo("cm_sk_generated_secret");
    assertThat(captor.getValue().isEnabled()).isTrue();
    assertThat(captor.getValue().getCreatedAt()).isEqualTo("2026-07-04T10:00:00Z");
    assertThat(result.appSecret()).isEqualTo("cm_sk_generated_secret");
    assertThat(result.created()).isTrue();
  }

  @Test
  void rejectsDuplicateClient() {
    org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
        .when(mapper).insert(org.mockito.Mockito.any());

    assertThatThrownBy(() -> service.createClient("miniapp_main", true))
        .isInstanceOf(ApiException.class)
        .hasMessage("小程序 AK 已存在。");
  }

  @Test
  void togglesClientEnabledState() {
    MiniappClientEntity existing = client("miniapp_main", "cm_sk_plain_secret", true);
    when(mapper.findByAppId("miniapp_main")).thenReturn(existing);

    PublicMiniappClient result = service.updateEnabled(" miniapp_main ", false);

    verify(mapper).updateEnabled("miniapp_main", false, "2026-07-04T10:00:00Z");
    assertThat(result.enabled()).isFalse();
    assertThat(result.appSecret()).isNull();
  }

  @Test
  void resetsSecretAndReturnsPlainSecretOnce() {
    MiniappClientEntity existing = client("miniapp_main", "cm_sk_old_secret", true);
    when(mapper.findByAppId("miniapp_main")).thenReturn(existing);

    PublicMiniappClient result = service.resetSecret("miniapp_main");

    verify(mapper).updateSecret("miniapp_main", "cm_sk_generated_secret", "2026-07-04T10:00:00Z");
    assertThat(result.appSecret()).isEqualTo("cm_sk_generated_secret");
    assertThat(result.appSecretPreview()).isEqualTo("cm_sk_genera...cret");
  }

  @Test
  void deletesClientByAk() {
    when(mapper.deleteByAppId("miniapp_main")).thenReturn(1);

    service.deleteClient(" miniapp_main ");

    verify(mapper).deleteByAppId("miniapp_main");
  }

  private static MiniappClientEntity client(String appId, String secret, boolean enabled) {
    MiniappClientEntity client = new MiniappClientEntity();
    client.setAppId(appId);
    client.setAppSecret(secret);
    client.setEnabled(enabled);
    client.setCreatedAt("2026-07-04T09:00:00Z");
    client.setUpdatedAt("2026-07-04T09:30:00Z");
    return client;
  }
}
