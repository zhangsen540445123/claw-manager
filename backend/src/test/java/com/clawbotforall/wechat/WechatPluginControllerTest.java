package com.clawbotforall.wechat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class WechatPluginControllerTest {

  @Test
  void batchCheckUsesFastLocalStatusWithoutLatestNetworkLookup() {
    InstanceCommandService commandService = mock(InstanceCommandService.class);
    WechatPluginService pluginService = mock(WechatPluginService.class);
    WechatPluginController controller = new WechatPluginController(commandService, pluginService);
    InstanceEntity instance = instance();
    PublicWechatPluginStatus status = new PublicWechatPluginStatus(
        true,
        "2.4.4",
        "",
        false,
        "installed",
        "微信插件已安装。",
        "",
        "2026-06-21T00:00:00Z"
    );
    when(commandService.listInstancesByIds(List.of("inst_1"))).thenReturn(List.of(instance));
    when(pluginService.status(instance, false)).thenReturn(status);

    controller.checkMany(
        new WechatPluginController.WechatPluginBatchRequest(List.of("inst_1"), ""),
        authentication()
    );

    verify(pluginService).status(instance, false);
    verify(pluginService, never()).status(instance, true);
  }

  private static InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("实例一");
    instance.setStatus("running");
    return instance;
  }

  private static TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new AuthenticatedAdmin(
            "admin_1",
            "admin@example.test",
            "Admin",
            false,
            "2026-06-21T00:00:00Z",
            "2026-06-21T00:00:00Z"
        ),
        null
    );
  }
}
