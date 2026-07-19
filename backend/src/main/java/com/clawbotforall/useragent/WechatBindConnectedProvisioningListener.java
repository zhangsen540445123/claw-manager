package com.clawbotforall.useragent;

import com.clawbotforall.wechat.WechatBindConnectedEvent;
import com.clawbotforall.wechat.WechatLogSanitizer;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WechatBindConnectedProvisioningListener {
  private static final Logger log = LoggerFactory.getLogger(WechatBindConnectedProvisioningListener.class);

  private final UserAgentIdentityService identityService;
  private final UserAgentProvisioningService provisioningService;
  private final Executor executor;

  @Autowired
  public WechatBindConnectedProvisioningListener(
      UserAgentIdentityService identityService,
      UserAgentProvisioningService provisioningService,
      @Qualifier(UserAgentExecutorConfiguration.EXECUTOR_BEAN_NAME) Executor executor
  ) {
    this.identityService = identityService;
    this.provisioningService = provisioningService;
    this.executor = executor;
  }

  @EventListener
  public void onConnected(WechatBindConnectedEvent event) {
    if (event == null) {
      return;
    }
    try {
      executor.execute(() -> provision(event));
    } catch (RuntimeException error) {
      logFailure(event, error);
    }
  }

  private void provision(WechatBindConnectedEvent event) {
    try {
      UserAgentIdentityResult identity = identityService.resolve(event.instanceId(), event.scannedWechatUserId());
      provisioningService.ensure(
          event.instanceId(),
          identity.agentId(),
          identity.openVikingUserId(),
          event.accountId(),
          event.scannedWechatUserId()
      );
    } catch (RuntimeException error) {
      logFailure(event, error);
    }
  }

  private void logFailure(WechatBindConnectedEvent event, RuntimeException error) {
    log.warn(
        "wechatBind.agentProvisioning.failed instanceId={} accountHash={} wechatUserHash={} errorType={}",
        normalize(event.instanceId()),
        WechatLogSanitizer.identityHashPreview(event.accountId()),
        WechatLogSanitizer.identityHashPreview(event.scannedWechatUserId()),
        error.getClass().getSimpleName()
    );
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
