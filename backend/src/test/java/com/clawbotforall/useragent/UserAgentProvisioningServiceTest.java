package com.clawbotforall.useragent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.miniapp.MiniappInstanceService;
import com.clawbotforall.wechat.OpenClawGatewayRpcService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserAgentProvisioningServiceTest {

  @Mock
  MiniappInstanceService instanceService;

  @Mock
  OpenClawGatewayRpcService gatewayRpcService;

  @Test
  void provisionsUserAgentThroughTheStrictGatewayRpc() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    when(instanceService.requireUsableApiInstance("inst_1")).thenReturn(instance);
    UserAgentProvisioningService service = new UserAgentProvisioningService(
        instanceService,
        gatewayRpcService
    );

    service.ensure(
        "inst_1",
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "account_1",
        "wechat_peer_1"
    );

    verify(gatewayRpcService).ensureUserAgent(
        instance,
        "user_0123456789abcdef0123456789abcdef",
        "wx_a67b392317ec3e01e7ee1285528f8a2e",
        "account_1",
        "wechat_peer_1"
    );
  }

  @Test
  void rejectsInvalidIdentityBeforeWritingQueueRequest() {
    UserAgentProvisioningService service = new UserAgentProvisioningService(
        instanceService,
        gatewayRpcService
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
    verify(gatewayRpcService, never()).ensureUserAgent(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString()
    );
  }
}
