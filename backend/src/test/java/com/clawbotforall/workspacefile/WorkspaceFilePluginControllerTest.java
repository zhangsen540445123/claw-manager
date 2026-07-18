package com.clawbotforall.workspacefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginBatchRequest;
import com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginVersionRequest;
import com.clawbotforall.externalapi.ApiChannelPluginService.ApiChannelPluginVersions;
import com.clawbotforall.externalapi.PublicApiChannelPluginStatus;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class WorkspaceFilePluginControllerTest {

  @Test
  void versionsPassForceRefreshToService() {
    InstanceCommandService instances = mock(InstanceCommandService.class);
    WorkspaceFilePluginService plugins = mock(WorkspaceFilePluginService.class);
    WorkspaceFilePluginController controller = new WorkspaceFilePluginController(instances, plugins);
    when(plugins.versions(true)).thenReturn(new ApiChannelPluginVersions("2026.7.16", List.of("2026.7.16")));

    Map<String, Object> response = controller.versions(true, authentication());

    assertThat(response.get("versions")).isEqualTo(new ApiChannelPluginVersions("2026.7.16", List.of("2026.7.16")));
    verify(plugins).versions(true);
  }

  @Test
  void batchCheckDeduplicatesIdsAndKeepsPartialFailures() {
    InstanceCommandService instances = mock(InstanceCommandService.class);
    WorkspaceFilePluginService plugins = mock(WorkspaceFilePluginService.class);
    WorkspaceFilePluginController controller = new WorkspaceFilePluginController(instances, plugins);
    InstanceEntity first = instance("inst_1");
    when(instances.requireInstance("inst_1")).thenReturn(first);
    when(instances.requireInstance("missing")).thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "实例不存在"));
    when(plugins.status(first, false)).thenReturn(status("installed"));

    Map<String, Object> response = controller.check(
        new ApiPluginBatchRequest(List.of(" inst_1 ", "inst_1", "missing"), ""),
        authentication()
    );

    @SuppressWarnings("unchecked")
    List<com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginBatchItem> items =
        (List<com.clawbotforall.externalapi.ApiChannelPluginController.ApiPluginBatchItem>) response.get("plugins");
    assertThat(items).hasSize(2);
    assertThat(items.getFirst().instanceId()).isEqualTo("inst_1");
    assertThat(items.getFirst().plugin().status()).isEqualTo("installed");
    assertThat(items.getLast().plugin().status()).isEqualTo("failed");
  }

  @Test
  void unsupportedOperationReturnsBadRequest() {
    WorkspaceFilePluginController controller = new WorkspaceFilePluginController(
        mock(InstanceCommandService.class), mock(WorkspaceFilePluginService.class));

    assertThatThrownBy(() -> controller.operate(
        "inst_1", "refresh", new ApiPluginVersionRequest(""), authentication()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("不支持的插件操作");
  }

  @Test
  void unauthenticatedVersionRequestIsRejected() {
    WorkspaceFilePluginController controller = new WorkspaceFilePluginController(
        mock(InstanceCommandService.class), mock(WorkspaceFilePluginService.class));

    assertThatThrownBy(() -> controller.versions(false, null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("请先登录");
  }

  private static InstanceEntity instance(String id) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId(id);
    return instance;
  }

  private static PublicApiChannelPluginStatus status(String status) {
    return new PublicApiChannelPluginStatus(
        true, "2026.7.16", "2026.7.16", false, status, "ok", "", "2026-07-12T00:00:00Z");
  }

  private static TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new AuthenticatedAdmin(
            "admin_1", "admin@example.test", "Admin", false,
            "2026-07-12T00:00:00Z", "2026-07-12T00:00:00Z"),
        null
    );
  }
}
