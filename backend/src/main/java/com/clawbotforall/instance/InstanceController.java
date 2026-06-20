package com.clawbotforall.instance;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.runtime.InstanceStats;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.wechat.WechatAccountSyncService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供管理员实例生命周期、模型、日志和微信账号管理 API。
 */
@RestController
@RequestMapping("/api/admin/instances")
public class InstanceController {

  private final InstanceQueryService instanceQueryService;
  private final InstanceCommandService instanceCommandService;
  private final InstanceProvisioningService provisioningService;
  private final InstanceEventPublisher eventPublisher;
  private final OpenClawRuntime openClawRuntime;
  private final WechatAccountSyncService wechatAccountSyncService;
  private final ModelAuthService modelAuthService;
  private final InstanceModelService instanceModelService;

  public InstanceController(
      InstanceQueryService instanceQueryService,
      InstanceCommandService instanceCommandService,
      InstanceProvisioningService provisioningService,
      InstanceEventPublisher eventPublisher,
      OpenClawRuntime openClawRuntime,
      WechatAccountSyncService wechatAccountSyncService,
      ModelAuthService modelAuthService,
      InstanceModelService instanceModelService
  ) {
    this.instanceQueryService = instanceQueryService;
    this.instanceCommandService = instanceCommandService;
    this.provisioningService = provisioningService;
    this.eventPublisher = eventPublisher;
    this.openClawRuntime = openClawRuntime;
    this.wechatAccountSyncService = wechatAccountSyncService;
    this.modelAuthService = modelAuthService;
    this.instanceModelService = instanceModelService;
  }

  /**
   * 列出全部 OpenClaw 实例。
   */
  @GetMapping
  public Map<String, Object> listInstances(Authentication authentication, HttpServletRequest request) {
    requireAdmin(authentication);
    return Map.of("instances", instanceQueryService.listAllInstances(request));
  }

  /**
   * 创建新的 OpenClaw 实例并启动 Gateway 就绪检查流程。
   */
  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> createInstance(
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.createInstance(payload);
    PublicInstance publicInstance = instanceQueryService.findPublicInstance(instance.getId(), request)
        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "实例创建后查询失败。"));
    eventPublisher.publishInstanceUpdated(publicInstance);
    provisioningService.startProvisioning(instance.getId());
    return Map.of("instance", publicInstance);
  }

  /**
   * 创建或启动实例运行容器。
   */
  @PostMapping("/{instanceId}/start")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> startInstance(
      @PathVariable String instanceId,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    provisioningService.startProvisioning(instance.getId());
    PublicInstance publicInstance = publicInstance(instance.getId(), request);
    return Map.of("instance", publicInstance);
  }

  /**
   * 重新启动 Gateway 就绪检查流程。
   */
  @PostMapping("/{instanceId}/restart-gateway")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> restartGateway(
      @PathVariable String instanceId,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    provisioningService.startProvisioning(instance.getId());
    return Map.of("instance", publicInstance(instance.getId(), request));
  }

  /**
   * 批量重新启动 Gateway 就绪检查流程。
   */
  @PostMapping("/batch/restart-gateway")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> restartGateways(
      @RequestBody(required = false) BatchInstancesRequest payload,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    List<BatchInstanceOperationResult> results = new ArrayList<>();
    for (String instanceId : payload == null || payload.instanceIds() == null ? List.<String>of() : payload.instanceIds()) {
      String normalizedInstanceId = instanceId == null ? "" : instanceId.trim();
      if (normalizedInstanceId.isBlank()) {
        continue;
      }
      try {
        InstanceEntity instance = instanceCommandService.requireInstance(normalizedInstanceId);
        provisioningService.startProvisioning(instance.getId());
        results.add(new BatchInstanceOperationResult(
            instance.getId(),
            "accepted",
            "Gateway 重启任务已提交。",
            publicInstance(instance.getId(), request)
        ));
      } catch (RuntimeException error) {
        results.add(new BatchInstanceOperationResult(
            normalizedInstanceId,
            "failed",
            message(error),
            null
        ));
      }
    }
    return Map.of("instances", results);
  }

  /**
   * 停止实例运行容器。
   */
  @PostMapping("/{instanceId}/stop")
  public Map<String, Object> stopInstance(
      @PathVariable String instanceId,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    openClawRuntime.stopInstance(instance);
    instanceCommandService.updateInstanceStatus(instance.getId(), "stopped");
    PublicInstance publicInstance = publicInstance(instanceId, request);
    eventPublisher.publishInstanceUpdated(publicInstance);
    return Map.of("instance", publicInstance);
  }

  /**
   * 返回实例容器日志。
   */
  @GetMapping("/{instanceId}/logs")
  public Map<String, Object> logs(
      @PathVariable String instanceId,
      @RequestParam(defaultValue = "200") int tail,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    return Map.of("logs", openClawRuntime.getLogs(instance, tail));
  }

  /**
   * 返回实例当前运行统计。
   */
  @GetMapping("/{instanceId}/stats")
  public Map<String, Object> stats(
      @PathVariable String instanceId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    InstanceStats stats = openClawRuntime.getStats(instance);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("stats", stats);
    return response;
  }

  /**
   * 更新实例主模型配置。
   */
  @PostMapping("/{instanceId}/model")
  public Map<String, Object> updatePrimaryModel(
      @PathVariable String instanceId,
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    InstanceModelUpdateResult result = instanceModelService.updatePrimary(instance, payload);
    return modelUpdateResponse(result, request);
  }

  /**
   * 向实例模型链添加模型。
   */
  @PostMapping("/{instanceId}/models")
  public Map<String, Object> addModel(
      @PathVariable String instanceId,
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    InstanceModelUpdateResult result = instanceModelService.addModel(instance, payload);
    return modelUpdateResponse(result, request);
  }

  /**
   * 在实例模型链中上移或下移模型。
   */
  @PostMapping("/{instanceId}/models/reorder")
  public Map<String, Object> reorderModels(
      @PathVariable String instanceId,
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    InstanceModelUpdateResult result = instanceModelService.reorder(instance, payload);
    return modelUpdateResponse(result, request);
  }

  @PostMapping("/{instanceId}/models/{modelIndex}/primary")
  public Map<String, Object> setPrimaryModel(
      @PathVariable String instanceId,
      @PathVariable String modelIndex,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    InstanceModelUpdateResult result = instanceModelService.setPrimary(instance, parseModelIndex(modelIndex));
    return modelUpdateResponse(result, request);
  }

  /**
   * 从实例模型链中删除备用模型。
   */
  @DeleteMapping("/{instanceId}/models/{modelIndex}")
  public Map<String, Object> deleteModel(
      @PathVariable String instanceId,
      @PathVariable String modelIndex,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    InstanceModelUpdateResult result = instanceModelService.deleteModel(instance, parseModelIndex(modelIndex));
    return modelUpdateResponse(result, request);
  }

  /**
   * 更新已绑定微信账号的备注。
   */
  @PutMapping("/{instanceId}/wechat-accounts/{accountId}")
  public Map<String, Object> updateWechatAccountRemark(
      @PathVariable String instanceId,
      @PathVariable String accountId,
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    String remark = sanitizeName(payload == null ? "" : payload.get("remark"));
    wechatAccountSyncService.updateRemark(instance, accountId, remark);
    PublicInstance publicInstance = publicInstance(instanceId, request);
    eventPublisher.publishInstanceUpdated(publicInstance);
    return Map.of("instance", publicInstance);
  }

  /**
   * 删除一个已绑定微信账号。
   */
  @DeleteMapping("/{instanceId}/wechat-accounts/{accountId}")
  public Map<String, Object> deleteWechatAccount(
      @PathVariable String instanceId,
      @PathVariable String accountId,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    wechatAccountSyncService.deleteAccount(instance, accountId);
    boolean gatewayRestarted = restartGatewayIfRunning(instance);
    PublicInstance publicInstance = publicInstance(instanceId, request);
    eventPublisher.publishInstanceUpdated(publicInstance);
    return Map.of("instance", publicInstance, "gatewayRestarted", gatewayRestarted);
  }

  /**
   * 删除实例下全部已绑定微信账号。
   */
  @PostMapping("/{instanceId}/wechat-unbind")
  public Map<String, Object> deleteAllWechatAccounts(
      @PathVariable String instanceId,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    boolean deletedAccounts = wechatAccountSyncService.deleteAllAccounts(instance);
    boolean gatewayRestarted = deletedAccounts && restartGatewayIfRunning(instance);
    PublicInstance publicInstance = publicInstance(instanceId, request);
    eventPublisher.publishInstanceUpdated(publicInstance);
    return Map.of("instance", publicInstance, "gatewayRestarted", gatewayRestarted);
  }

  /**
   * 启动交互式模型认证流程。
   */
  @PostMapping("/{instanceId}/model-auth/start")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> startModelAuth(
      @PathVariable String instanceId,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    modelAuthService.start(instance);
    return Map.of("instance", publicInstance(instanceId, request));
  }

  /**
   * 向待处理的模型认证流程发送管理员输入。
   */
  @PostMapping("/{instanceId}/model-auth/input")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> sendModelAuthInput(
      @PathVariable String instanceId,
      @RequestBody(required = false) Map<String, Object> payload,
      Authentication authentication,
      HttpServletRequest request
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    Object rawText = payload == null ? null : payload.get("text");
    modelAuthService.sendInput(instance, rawText == null ? "" : String.valueOf(rawText));
    return Map.of("instance", publicInstance(instanceId, request));
  }

  /**
   * 取消正在进行的模型认证流程。
   */
  @PostMapping("/{instanceId}/model-auth/cancel")
  public Map<String, Object> cancelModelAuth(
      @PathVariable String instanceId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    InstanceEntity instance = instanceCommandService.requireInstance(instanceId);
    modelAuthService.cancel(instance);
    return Map.of("ok", true);
  }

  private PublicInstance publicInstance(String instanceId, HttpServletRequest request) {
    return instanceQueryService.findPublicInstance(instanceId, request)
        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "实例查询失败。"));
  }

  private Map<String, Object> modelUpdateResponse(
      InstanceModelUpdateResult result,
      HttpServletRequest request
  ) {
    if (result.restartRequired()) {
      provisioningService.startProvisioning(result.instance().getId());
    }
    PublicInstance publicInstance = publicInstance(result.instance().getId(), request);
    eventPublisher.publishInstanceUpdated(publicInstance);
    return Map.of("instance", publicInstance);
  }

  private boolean restartGatewayIfRunning(InstanceEntity instance) {
    RuntimeState runtimeState = openClawRuntime.inspectInstance(instance);
    if (!runtimeState.running()) {
      return false;
    }
    provisioningService.startProvisioning(instance.getId());
    return true;
  }

  private static AuthenticatedAdmin requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin admin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
    return admin;
  }

  private static String sanitizeName(Object value) {
    String normalized = value == null ? "" : String.valueOf(value).trim().replaceAll("\\s+", " ");
    return normalized.substring(0, Math.min(60, normalized.length()));
  }

  private static String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? "实例操作失败。" : message;
  }

  private static int parseModelIndex(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "模型索引无效。");
    }
  }

  public record BatchInstancesRequest(List<String> instanceIds) {}

  public record BatchInstanceOperationResult(
      String instanceId,
      String status,
      String message,
      PublicInstance instance
  ) {}
}
