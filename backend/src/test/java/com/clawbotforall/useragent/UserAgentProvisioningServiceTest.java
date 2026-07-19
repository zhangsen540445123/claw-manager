package com.clawbotforall.useragent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.externalapi.ExternalApiQueueService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.miniapp.MiniappInstanceService;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserAgentProvisioningServiceTest {

  @Mock
  MiniappInstanceService instanceService;

  @Mock
  ExternalApiQueueService queueService;

  @Test
  void sendsEnsureUserAgentOperationAndWaitsForQueueResponse() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(instance);
    when(queueService.sendApiChannelMessage(org.mockito.ArgumentMatchers.eq(instance), anyMap()))
        .thenReturn(Map.of("ok", true));
    UserAgentProvisioningService service = new UserAgentProvisioningService(
        instanceService,
        queueService,
        Runnable::run
    );

    service.ensure(
        "inst_1",
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "account_1",
        "wechat_peer_1"
    );

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
    verify(queueService).sendApiChannelMessage(org.mockito.ArgumentMatchers.eq(instance), payloadCaptor.capture());
    Map<String, Object> payload = payloadCaptor.getValue();
    assertThat(payload)
        .containsEntry("operation", "ensure_user_agent")
        .containsEntry("agentId", "user_0123456789abcdef0123456789abcdef")
        .containsEntry("openVikingUserId", "wx_a67b392317ec3e01e7ee1285528f8a2e")
        .containsEntry("wechatAccountId", "account_1")
        .containsEntry("wechatPeerId", "wechat_peer_1");
    assertThat(payload.get("requestId"))
        .asString()
        .matches("ensure_[0-9a-f]{32}");
  }

  @Test
  void rejectsInvalidIdentityBeforeWritingQueueRequest() {
    UserAgentProvisioningService service = new UserAgentProvisioningService(
        instanceService,
        queueService,
        Runnable::run
    );

    assertThatThrownBy(() -> service.ensure(
        "inst_1",
        "agent_main",
        "wx_invalid",
        "account_1",
        "wechat_peer_1"
    ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("用户 Agent 身份格式无效。");
    verify(instanceService, never()).requireUsableApiInstance("inst_1");
    verify(queueService, never()).sendApiChannelMessage(org.mockito.ArgumentMatchers.any(), anyMap());
  }

  @Test
  void asynchronousProvisioningFailureDoesNotEscapeCaller() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(instance);
    when(queueService.sendApiChannelMessage(org.mockito.ArgumentMatchers.eq(instance), anyMap()))
        .thenThrow(new IllegalStateException("queue failed"));
    UserAgentProvisioningService service = new UserAgentProvisioningService(
        instanceService,
        queueService,
        Runnable::run
    );

    assertThatCode(() -> service.ensureAsync(
        "inst_1",
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "account_1",
        "wechat_peer_1"
    )).doesNotThrowAnyException();
  }

  @Test
  void rejectedAsynchronousProvisioningDoesNotEscapeCaller() {
    UserAgentProvisioningService service = new UserAgentProvisioningService(
        instanceService,
        queueService,
        task -> {
          throw new RejectedExecutionException("queue full");
        }
    );

    assertThatCode(() -> service.ensureAsync(
        "inst_1",
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "account_1",
        "wechat_peer_1"
    )).doesNotThrowAnyException();
  }
}
