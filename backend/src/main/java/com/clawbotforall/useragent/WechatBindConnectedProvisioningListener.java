package com.clawbotforall.useragent;

import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.wechat.WechatBindConnectedEvent;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WechatBindConnectedProvisioningListener {
  private final UserAgentIdentityService identityService;
  private final UserAgentProvisioningService provisioningService;
  private final MiniappUserBindingMapper miniappBindingMapper;

  @Autowired
  public WechatBindConnectedProvisioningListener(
      UserAgentIdentityService identityService,
      UserAgentProvisioningService provisioningService,
      MiniappUserBindingMapper miniappBindingMapper
  ) {
    this.identityService = identityService;
    this.provisioningService = provisioningService;
    this.miniappBindingMapper = miniappBindingMapper;
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
    String miniappOpenidHash = event.miniappOpenidHash() == null ? "" : event.miniappOpenidHash().trim();
    if (!miniappOpenidHash.isBlank()) {
      provisioningService.ensureApiBinding(
          event.instanceId(),
          identity.agentId(),
          identity.openVikingUserId(),
          miniappOpenidHash
      );
      String now = Instant.now().toString();
      int updated = miniappBindingMapper.markConnected(
          miniappOpenidHash,
          event.scannedWechatUserId(),
          identity.agentId(),
          identity.openVikingUserId(),
          now,
          now
      );
      if (updated != 1) {
        throw new IllegalStateException("小程序绑定记录不存在，无法完成微信绑定。");
      }
    }
  }
}
