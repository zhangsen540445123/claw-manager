package com.clawbotforall.useragent;

import com.clawbotforall.wechat.WechatBindConnectedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WechatBindConnectedProvisioningListener {
  private final UserAgentIdentityService identityService;
  private final UserAgentProvisioningService provisioningService;

  @Autowired
  public WechatBindConnectedProvisioningListener(
      UserAgentIdentityService identityService,
      UserAgentProvisioningService provisioningService
  ) {
    this.identityService = identityService;
    this.provisioningService = provisioningService;
  }

  @EventListener
  public void onConnected(WechatBindConnectedEvent event) {
    if (event == null) {
      return;
    }
    provision(event);
  }

  private void provision(WechatBindConnectedEvent event) {
    UserAgentIdentityResult identity = identityService.resolve(event.instanceId(), event.scannedWechatUserId());
    provisioningService.ensure(
        event.instanceId(),
        identity.agentId(),
        identity.openVikingUserId(),
        event.accountId(),
        event.scannedWechatUserId()
    );
    if (event.miniappOpenidHash() != null && !event.miniappOpenidHash().isBlank()) {
      provisioningService.ensureApiBinding(
          event.instanceId(),
          identity.agentId(),
          identity.openVikingUserId(),
          event.miniappOpenidHash()
      );
    }
  }
}
