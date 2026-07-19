package com.clawbotforall.useragent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.miniapp.MiniappInstanceService;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingIdentityService;
import com.clawbotforall.openviking.OpenVikingSenderIdentity;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserAgentIdentityServiceTest {

  @Mock
  UserAgentIdentityMapper mapper;

  @Mock
  MiniappInstanceService instanceService;

  @Mock
  OpenVikingSettingsService settingsService;

  @Mock
  OpenVikingIdentityService identityService;

  UserAgentIdentityService service;

  @BeforeEach
  void setUp() {
    service = new UserAgentIdentityService(
        mapper,
        instanceService,
        settingsService,
        identityService,
        Clock.fixed(Instant.parse("2026-07-19T08:00:00Z"), ZoneOffset.UTC)
    );
  }

  @Test
  void createsUserLevelIdentityFromCurrentDatabaseSalt() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(instance);
    when(mapper.findByWechatUserId("wechat_user_1")).thenReturn(null);
    when(settingsService.effectiveSettings()).thenReturn(settings("salt_1"));
    when(identityService.resolveSenderIdentity("wechat_user_1", "salt_1"))
        .thenReturn(java.util.Optional.of(new OpenVikingSenderIdentity(
            "wechat_user_1", "0123456789abcdef0123456789abcdef", "wx_0123456789abcdef0123456789abcdef")));
    when(mapper.insert(any(UserAgentIdentityEntity.class))).thenReturn(1);

    UserAgentIdentityResult result = service.resolve("inst_1", "wechat_user_1");

    assertThat(result.created()).isTrue();
    assertThat(result.agentId()).matches("user_[0-9a-f]{32}");
    assertThat(result.openVikingUserId()).isEqualTo("wx_0123456789abcdef0123456789abcdef");
    ArgumentCaptor<UserAgentIdentityEntity> captor = ArgumentCaptor.forClass(UserAgentIdentityEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getWechatUserId()).isEqualTo("wechat_user_1");
    assertThat(captor.getValue().getCreatedAt()).isEqualTo("2026-07-19T08:00:00Z");
    assertThat(captor.getValue().getUpdatedAt()).isEqualTo("2026-07-19T08:00:00Z");
  }

  @Test
  void reusesPersistedIdentityWithoutRederivingAfterSaltChanges() {
    UserAgentIdentityEntity existing = identity(
        "user_11111111111111111111111111111111",
        "wechat_user_1",
        "wx_old_identity"
    );
    when(instanceService.requireUsableApiInstance("inst_2")).thenReturn(new InstanceEntity());
    when(mapper.findByWechatUserId("wechat_user_1")).thenReturn(existing);

    UserAgentIdentityResult result = service.resolve("inst_2", "wechat_user_1");

    assertThat(result.agentId()).isEqualTo(existing.getAgentId());
    assertThat(result.openVikingUserId()).isEqualTo("wx_old_identity");
    assertThat(result.created()).isFalse();
    verify(settingsService, never()).effectiveSettings();
    verify(identityService, never()).resolveSenderIdentity(any(), any());
    verify(mapper, never()).insert(any());
  }

  @Test
  void returnsWinningIdentityWhenConcurrentInsertAlreadyCreatedIt() {
    UserAgentIdentityEntity winner = identity(
        "user_22222222222222222222222222222222",
        "wechat_user_1",
        "wx_0123456789abcdef0123456789abcdef"
    );
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(new InstanceEntity());
    when(mapper.findByWechatUserId("wechat_user_1")).thenReturn(null);
    when(settingsService.effectiveSettings()).thenReturn(settings("salt_1"));
    when(identityService.resolveSenderIdentity("wechat_user_1", "salt_1"))
        .thenReturn(java.util.Optional.of(new OpenVikingSenderIdentity(
            "wechat_user_1", "0123456789abcdef0123456789abcdef", winner.getOpenvikingUserId())));
    when(mapper.insert(any(UserAgentIdentityEntity.class)))
        .thenThrow(new DuplicateKeyException("duplicate identity"));
    when(mapper.findByWechatUserIdForUpdate("wechat_user_1")).thenReturn(winner);

    UserAgentIdentityResult result = service.resolve("inst_1", "wechat_user_1");

    assertThat(result.agentId()).isEqualTo(winner.getAgentId());
    assertThat(result.created()).isFalse();
  }

  @Test
  void doesNotSwallowNonDuplicateInsertFailures() {
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(new InstanceEntity());
    when(mapper.findByWechatUserId("wechat_user_1")).thenReturn(null);
    when(settingsService.effectiveSettings()).thenReturn(settings("salt_1"));
    when(identityService.resolveSenderIdentity("wechat_user_1", "salt_1"))
        .thenReturn(java.util.Optional.of(new OpenVikingSenderIdentity(
            "wechat_user_1", "0123456789abcdef0123456789abcdef", "wx_0123456789abcdef0123456789abcdef")));
    DataIntegrityViolationException failure = new DataIntegrityViolationException("invalid row");
    when(mapper.insert(any(UserAgentIdentityEntity.class))).thenThrow(failure);

    assertThatThrownBy(() -> service.resolve("inst_1", "wechat_user_1"))
        .isSameAs(failure);
    verify(mapper, never()).findByWechatUserIdForUpdate("wechat_user_1");
  }

  @Test
  void rejectsBlankWechatUserIdBeforePersistingIdentity() {
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(new InstanceEntity());

    assertThatThrownBy(() -> service.resolve("inst_1", "  "))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("微信用户");

    verify(mapper, never()).insert(any());
  }

  private static UserAgentIdentityEntity identity(String agentId, String wechatUserId, String openVikingUserId) {
    UserAgentIdentityEntity entity = new UserAgentIdentityEntity();
    entity.setAgentId(agentId);
    entity.setWechatUserId(wechatUserId);
    entity.setOpenvikingUserId(openVikingUserId);
    entity.setCreatedAt("2026-07-19T07:00:00Z");
    entity.setUpdatedAt("2026-07-19T07:00:00Z");
    return entity;
  }

  private static OpenVikingEffectiveSettings settings(String salt) {
    return new OpenVikingEffectiveSettings("", true, "account_1", salt, "", "", "", "");
  }
}
