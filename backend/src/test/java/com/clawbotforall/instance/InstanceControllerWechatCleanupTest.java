package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.wechat.PublicWechatUserCleanupOperation;
import com.clawbotforall.wechat.WechatAccountSyncService;
import com.clawbotforall.wechat.WechatUserCleanupOperationEntity;
import com.clawbotforall.wechat.WechatUserCleanupService;
import com.clawbotforall.wechat.WechatUserResidueScanner;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class InstanceControllerWechatCleanupTest {
  private InstanceDeleteOperationController deleteOperationController;
  @Mock InstanceQueryService instanceQueryService;
  @Mock InstanceCommandService instanceCommandService;
  @Mock InstanceProvisioningService provisioningService;
  @Mock InstanceEventPublisher eventPublisher;
  @Mock OpenClawRuntime openClawRuntime;
  @Mock WechatAccountSyncService wechatAccountSyncService;
  @Mock WechatUserCleanupService cleanupService;
  @Mock WechatUserResidueScanner residueScanner;
  @Mock ModelAuthService modelAuthService;
  @Mock InstanceModelService instanceModelService;
  @Mock InstanceDeletionService instanceDeletionService;
  @Mock HttpServletRequest request;
  private InstanceController controller;
  private InstanceEntity instance;
  private PublicInstance publicInstance;

  @BeforeEach
  void setUp() {
    controller = new InstanceController(
        instanceQueryService, instanceCommandService, provisioningService, eventPublisher,
        openClawRuntime, wechatAccountSyncService, cleanupService, residueScanner,
        modelAuthService, instanceModelService, instanceDeletionService);
    deleteOperationController = new InstanceDeleteOperationController(instanceDeletionService);
    instance = new InstanceEntity();
    instance.setId("inst-1");
    publicInstance = new PublicInstance(
        "inst-1", "实例一", "instance-1", "running", 18789, "", "runner", "", "", "",
        null, null, List.of(), null, Map.of(), null);
  }

  @Test
  void deletingWechatAccountStartsRecoverableCleanup() {
    stubInstanceLookup();
    WechatUserCleanupOperationEntity operation = operation("op-1");
    when(cleanupService.start(instance, "account-1", "user_center")).thenReturn(operation);

    Map<String, Object> response = controller.deleteWechatAccount(
        "inst-1", "account-1", authentication(), request);

    verify(cleanupService).start(instance, "account-1", "user_center");
    verify(eventPublisher).publishInstanceUpdated(publicInstance);
    assertThat(response.get("operation")).isEqualTo(PublicWechatUserCleanupOperation.from(operation));
    assertThat(response.get("instance")).isEqualTo(publicInstance);
  }

  @Test
  void deletingAllWechatAccountsStartsIndependentCleanupOperationsIncludingAttributedResidues() {
    stubInstanceLookup();
    WechatUserCleanupOperationEntity active = operation("op-active");
    WechatUserCleanupOperationEntity ghost = operation("op-ghost");
    when(cleanupService.startAll(instance)).thenReturn(List.of(active));
    when(residueScanner.scanInstance(instance))
        .thenReturn(new WechatUserResidueScanner.ScanResult(List.of("op-active", "op-ghost"), List.of()));
    when(cleanupService.find("op-ghost")).thenReturn(ghost);

    Map<String, Object> response = controller.deleteAllWechatAccounts(
        "inst-1", authentication(), request);

    verify(cleanupService).startAll(instance);
    verify(residueScanner).scanInstance(instance);
    verify(cleanupService).find("op-ghost");
    verify(eventPublisher).publishInstanceUpdated(publicInstance);
    assertThat(response.get("operations")).isEqualTo(List.of(
        PublicWechatUserCleanupOperation.from(active),
        PublicWechatUserCleanupOperation.from(ghost)));
    assertThat(response.get("instance")).isEqualTo(publicInstance);
  }

  @Test
  void deletingInstanceWithoutForceReturnsConflictWhenUsersExist() {
    ApiException conflict = new ApiException(HttpStatus.CONFLICT, "该实例已绑定微信账号，需要强确认后删除。");
    when(instanceDeletionService.start("inst-1", false)).thenThrow(conflict);

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            controller.deleteInstance("inst-1", false, authentication()))
        .isSameAs(conflict);
  }

  @Test
  void deletingInstanceWithForceReturnsDeleteOperation() {
    InstanceDeleteOperationEntity operation = deleteOperation("delete-1", "deleting", "validated");
    when(instanceDeletionService.start("inst-1", true)).thenReturn(operation);

    Map<String, Object> response = controller.deleteInstance("inst-1", true, authentication());

    verify(instanceDeletionService).start("inst-1", true);
    assertThat(response.get("operation")).isEqualTo(PublicInstanceDeleteOperation.from(operation));
  }

  @Test
  void retryingInstanceDeleteReturnsDeleteOperation() {
    InstanceDeleteOperationEntity operation = deleteOperation("delete-1", "deleting", "validated");
    when(instanceDeletionService.retry("delete-1")).thenReturn(operation);

    Map<String, Object> response = deleteOperationController.retry("delete-1", authentication());

    verify(instanceDeletionService).retry("delete-1");
    assertThat(response.get("operation")).isEqualTo(PublicInstanceDeleteOperation.from(operation));
  }

  private void stubInstanceLookup() {
    when(instanceCommandService.requireInstance("inst-1")).thenReturn(instance);
    when(instanceQueryService.findPublicInstance("inst-1", request)).thenReturn(Optional.of(publicInstance));
  }

  private static WechatUserCleanupOperationEntity operation(String id) {
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId(id);
    operation.setInstanceId("inst-1");
    operation.setStatus("completed");
    operation.setStage("completed");
    return operation;
  }

  private static InstanceDeleteOperationEntity deleteOperation(String id, String status, String stage) {
    InstanceDeleteOperationEntity operation = new InstanceDeleteOperationEntity();
    operation.setOperationId(id);
    operation.setInstanceId("inst-1");
    operation.setInstanceName("实例一");
    operation.setContainerName("runner");
    operation.setForce(true);
    operation.setStatus(status);
    operation.setStage(stage);
    operation.setWechatAccountCount(1);
    operation.setMiniappBindingCount(1);
    operation.setCreatedAt("now");
    operation.setUpdatedAt("now");
    return operation;
  }

  private static TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new AuthenticatedAdmin("admin-1", "admin@example.test", "Admin", false, "now", "now"), null);
  }
}


