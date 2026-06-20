package com.clawbotforall.instance;

import com.clawbotforall.model.ModelPresetEntity;
import com.clawbotforall.model.ModelPresetMapper;
import com.clawbotforall.model.ModelPresetNormalizer;
import com.clawbotforall.model.NormalizedModelSelection;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 负责实例记录和创建进度状态的事务性变更。
 */
@Service
public class InstanceCommandService {

  private static final Logger log = LoggerFactory.getLogger(InstanceCommandService.class);

  private final InstanceMutationMapper instanceMutationMapper;
  private final InstanceAggregateMapper instanceAggregateMapper;
  private final ModelPresetMapper modelPresetMapper;
  private final ModelPresetNormalizer modelPresetNormalizer;
  private final InstancePortAllocator portAllocator;
  private final InstanceRecordFactory recordFactory;

  public InstanceCommandService(
      InstanceMutationMapper instanceMutationMapper,
      InstanceAggregateMapper instanceAggregateMapper,
      ModelPresetMapper modelPresetMapper,
      ModelPresetNormalizer modelPresetNormalizer,
      InstancePortAllocator portAllocator,
      InstanceRecordFactory recordFactory
  ) {
    this.instanceMutationMapper = instanceMutationMapper;
    this.instanceAggregateMapper = instanceAggregateMapper;
    this.modelPresetMapper = modelPresetMapper;
    this.modelPresetNormalizer = modelPresetNormalizer;
    this.portAllocator = portAllocator;
    this.recordFactory = recordFactory;
  }

  /**
   * 创建并持久化新的实例聚合。
   */

  @Transactional
  public InstanceEntity createInstance(
      Map<String, Object> payload
  ) {
    Map<String, Object> body = payload == null ? Map.of() : payload;
    String name = recordFactory.sanitizeName(body.get("name"));
    if (name.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "实例名称不能为空。");
    }

    ResolvedRuntimeModel runtimeModel = resolveRuntimeModel(body);
    int port = portAllocator.findAvailablePort();
    InstanceCreationDraft draft = recordFactory.create(name, runtimeModel.model(), runtimeModel.presetId(), port);

    try {
      instanceMutationMapper.insertInstance(draft.instance());
      instanceMutationMapper.insertModel(draft.model());
      instanceMutationMapper.insertProvisioning(draft.provisioning());
      instanceMutationMapper.insertModelAuth(draft.modelAuth());
    } catch (DuplicateKeyException error) {
      throw new ApiException(HttpStatus.CONFLICT, "实例端口或容器名称冲突，请重试。");
    }

    log.info(
        "OpenClaw 实例记录已创建：instanceId={}, name={}, port={}, presetId={}",
        draft.instance().getId(),
        draft.instance().getName(),
        draft.instance().getPort(),
        runtimeModel.presetId()
    );
    return draft.instance();
  }

  /**
   * 加载实例；不存在时抛出未找到的 API 错误。
   */

  @Transactional(readOnly = true)
  public InstanceEntity requireInstance(String instanceId) {
    InstanceEntity instance = instanceAggregateMapper.findById(instanceId);
    if (instance == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "实例不存在。");
    }
    return instance;
  }

  /**
   * 返回实例已持久化的模型记录。
   */

  @Transactional(readOnly = true)
  public List<InstanceModelEntity> listModels(String instanceId) {
    return instanceAggregateMapper.listModelsByInstanceIds(List.of(instanceId));
  }

  /**
   * 持久化实例运行状态变更。
   */

  @Transactional
  public InstanceEntity updateInstanceStatus(String instanceId, String status) {
    InstanceEntity instance = requireInstance(instanceId);
    String now = Instant.now().toString();
    instanceMutationMapper.updateInstanceStatus(instanceId, status, now);
    instance.setStatus(status);
    instance.setUpdatedAt(now);
    return instance;
  }

  /**
   * 持久化实例创建进度，并更新运行状态数据。
   */

  @Transactional
  public ProvisioningUpdate updateProvisioning(
      String instanceId,
      String runtimeStatus,
      String status,
      int percent,
      String stage,
      String message,
      String gatewayStartedAt
  ) {
    InstanceEntity instance = requireInstance(instanceId);
    String now = Instant.now().toString();
    InstanceProvisioningEntity provisioning = new InstanceProvisioningEntity();
    provisioning.setInstanceId(instanceId);
    provisioning.setStatus(status);
    provisioning.setPercent(percent);
    provisioning.setStage(stage);
    provisioning.setMessage(message);
    provisioning.setGatewayStartedAt(gatewayStartedAt);
    provisioning.setUpdatedAt(now);
    instanceMutationMapper.updateProvisioning(provisioning);
    String nextRuntimeStatus = runtimeStatus == null || runtimeStatus.isBlank()
        ? instance.getStatus()
        : runtimeStatus;
    instanceMutationMapper.updateInstanceStatus(instanceId, nextRuntimeStatus, now);
    instance.setStatus(nextRuntimeStatus);
    instance.setUpdatedAt(now);
    return new ProvisioningUpdate(instance, provisioning);
  }

  /**
   * Gateway 就绪后，把已有微信账号的实例通道状态标记为可用。
   */

  @Transactional
  public void markWechatRuntimeReadyIfPaired(String instanceId) {
    List<WechatPairedAccountEntity> accounts = instanceAggregateMapper.listWechatAccountsByInstanceIds(List.of(instanceId));
    if (accounts.isEmpty()) {
      return;
    }
    String now = Instant.now().toString();
    for (WechatPairedAccountEntity account : accounts) {
      WechatAccountChannelEntity channel = new WechatAccountChannelEntity();
      channel.setAccountId(account.getAccountId());
      channel.setInstanceId(account.getInstanceId());
      channel.setWechatUserId(account.getWechatUserId());
      channel.setStatus("ready");
      channel.setMessage("");
      channel.setOutputSnippet("");
      channel.setLastStartedAt(now);
      channel.setLastErrorAt(null);
      channel.setUpdatedAt(now);
      instanceMutationMapper.upsertWechatAccountChannel(channel);
    }
    log.info("已将配对微信账号标记为运行可用：instanceId={}, accountCount={}", instanceId, accounts.size());
  }

  private ResolvedRuntimeModel resolveRuntimeModel(Map<String, Object> payload) {
    String presetId = trimString(payload.get("presetId"));
    if (presetId.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请选择模型预设。");
    }
    ModelPresetEntity preset = modelPresetMapper.findById(presetId);
    if (preset == null) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "所选模型预设不存在。");
    }
    NormalizedModelSelection model = modelPresetNormalizer.normalizePreset(preset);
    modelPresetNormalizer.validateRuntimeUsable(model, preset.getName());
    return new ResolvedRuntimeModel(model, presetId);
  }

  private static String trimString(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private record ResolvedRuntimeModel(
      NormalizedModelSelection model,
      String presetId
  ) {}
}
