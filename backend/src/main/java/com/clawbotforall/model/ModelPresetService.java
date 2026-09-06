package com.clawbotforall.model;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceModelAuthEntity;
import com.clawbotforall.instance.InstanceModelEntity;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceProvisioningService;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 管理模型预设创建、更新、删除、默认选择以及保存后向全部实例同步模型链。
 */
@Service
public class ModelPresetService {

  private static final Logger log = LoggerFactory.getLogger(ModelPresetService.class);

  private final ModelPresetMapper modelPresetMapper;
  private final ModelPresetNormalizer normalizer;
  private final InstanceAggregateMapper instanceAggregateMapper;
  private final InstanceMutationMapper instanceMutationMapper;
  private final InstanceFileService fileService;
  private final OpenClawRuntime openClawRuntime;
  private final InstanceProvisioningService provisioningService;

  public ModelPresetService(
      ModelPresetMapper modelPresetMapper,
      ModelPresetNormalizer normalizer,
      InstanceAggregateMapper instanceAggregateMapper,
      InstanceMutationMapper instanceMutationMapper,
      InstanceFileService fileService,
      OpenClawRuntime openClawRuntime,
      InstanceProvisioningService provisioningService
  ) {
    this.modelPresetMapper = modelPresetMapper;
    this.normalizer = normalizer;
    this.instanceAggregateMapper = instanceAggregateMapper;
    this.instanceMutationMapper = instanceMutationMapper;
    this.fileService = fileService;
    this.openClawRuntime = openClawRuntime;
    this.provisioningService = provisioningService;
  }

  /**
   * 以公共响应形式返回全部预设。
   */

  @Transactional(readOnly = true)
  public List<PublicModelPreset> listPublicPresets() {
    return modelPresetMapper.listAll().stream()
        .map(preset -> PublicModelPreset.from(
            preset,
            normalizer.isConfigured(preset),
            ModelPresetFallbacks.parse(preset.getFallbackPresetIds())
        ))
        .toList();
  }

  /**
   * 查询某个预设当前被哪些实例模型引用。
   */
  @Transactional(readOnly = true)
  public PublicModelPresetUsage usage(String presetId) {
    requirePreset(presetId);
    return new PublicModelPresetUsage(referencedInstances(presetId).stream()
        .map(ReferencedPresetInstance::publicUsage)
        .toList());
  }

  /**
   * 根据规范化后的管理员输入创建模型预设。
   */

  @Transactional
  public PublicModelPreset createPreset(Map<String, Object> payload) {
    String name = normalizer.sanitizeName(payload == null ? null : payload.get("name"));
    if (name.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "预设名称不能为空。");
    }

    NormalizedModelSelection model = normalizer.normalizePayload(payload, null);
    boolean isDefault = normalizer.parseBooleanFlag(
        payload == null ? null : payload.get("isDefault"),
        modelPresetMapper.countAll() == 0
    );
    List<String> fallbackPresetIds = validateFallbackReferences(
        null,
        ModelPresetFallbacks.normalizeRequest(
            payload == null ? null : payload.get("fallbackPresetIds")
        )
    );

    ModelPresetEntity preset = new ModelPresetEntity();
    preset.setId("preset_" + Long.toString(System.currentTimeMillis(), 36)
        + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6));
    preset.setName(name);
    preset.setDefault(isDefault);
    applyModel(preset, model);
    preset.setFallbackPresetIds(ModelPresetFallbacks.toJsonOrNull(fallbackPresetIds));
    preset.setCreatedAt(Instant.now().toString());

    if (preset.isDefault()) {
      modelPresetMapper.clearDefault();
    }
    modelPresetMapper.insert(preset);
    log.info(
        "模型预设已创建：presetId={}, name={}, providerKey={}, modelId={}, default={}, fallbackCount={}",
        preset.getId(),
        preset.getName(),
        preset.getProviderKey(),
        preset.getModelId(),
        preset.isDefault(),
        fallbackPresetIds.size()
    );
    return PublicModelPreset.from(preset, normalizer.isConfigured(preset), fallbackPresetIds);
  }

  /**
   * 更新模型预设，并规范化密钥处理。
   */

  @Transactional
  public ModelPresetUpdateResult updatePreset(String presetId, Map<String, Object> payload) {
    String name = normalizer.sanitizeName(payload == null ? null : payload.get("name"));
    if (name.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "预设名称不能为空。");
    }

    ModelPresetEntity preset = requirePreset(presetId);

    NormalizedModelSelection model = normalizer.normalizePayload(payload, preset);
    boolean isDefault = normalizer.parseBooleanFlag(
        payload == null ? null : payload.get("isDefault"),
        preset.isDefault()
    );
    List<String> fallbackPresetIds;
    if (payload != null && payload.containsKey("fallbackPresetIds")) {
      fallbackPresetIds = validateFallbackReferences(
          presetId,
          ModelPresetFallbacks.normalizeRequest(payload.get("fallbackPresetIds"))
      );
    } else {
      fallbackPresetIds = ModelPresetFallbacks.parse(preset.getFallbackPresetIds());
    }

    preset.setName(name);
    preset.setDefault(isDefault);
    applyModel(preset, model);
    preset.setFallbackPresetIds(ModelPresetFallbacks.toJsonOrNull(fallbackPresetIds));
    if (preset.isDefault()) {
      modelPresetMapper.clearDefault();
    }
    modelPresetMapper.update(preset);

    boolean shouldSync = normalizer.parseBooleanFlag(
        payload == null ? null : payload.get("syncReferencedInstances"),
        false
    );
    ModelPresetSyncResult sync = shouldSync
        ? syncAllInstancesWithChain(preset)
        : ModelPresetSyncResult.notRequested(0);
    log.info(
        "模型预设已更新：presetId={}, name={}, syncRequested={}, affectedInstances={}, restartedInstances={}, fallbackCount={}",
        preset.getId(),
        preset.getName(),
        shouldSync,
        sync.affectedInstances(),
        sync.restartedInstanceIds().size(),
        fallbackPresetIds.size()
    );
    return new ModelPresetUpdateResult(
        PublicModelPreset.from(preset, normalizer.isConfigured(preset), fallbackPresetIds),
        sync
    );
  }

  @Transactional
  public void setDefault(String presetId) {
    if (modelPresetMapper.findById(presetId) == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "预设不存在。");
    }
    modelPresetMapper.clearDefault();
    modelPresetMapper.setDefault(presetId);
    log.info("默认模型预设已切换：presetId={}", presetId);
  }

  /**
   * 删除预设；若被其它预设作为 Fallback 引用，则自动从引用方列表移除并持久化。
   *
   * @return 受影响（引用被删预设作为 Fallback）的其它预设名称列表
   */

  @Transactional
  public List<String> deletePreset(String presetId) {
    ModelPresetEntity preset = modelPresetMapper.findById(presetId);
    if (preset == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "预设不存在。");
    }

    List<String> affectedNames = new ArrayList<>();
    for (ModelPresetEntity candidate : modelPresetMapper.listAll()) {
      if (candidate.getId().equals(presetId)) {
        continue;
      }
      List<String> fallbacks = new ArrayList<>(
          ModelPresetFallbacks.parse(candidate.getFallbackPresetIds())
      );
      if (fallbacks.remove(presetId)) {
        candidate.setFallbackPresetIds(ModelPresetFallbacks.toJsonOrNull(fallbacks));
        modelPresetMapper.update(candidate);
        affectedNames.add(candidate.getName());
      }
    }

    boolean deletingDefault = preset.isDefault();
    modelPresetMapper.delete(presetId);
    if (deletingDefault && modelPresetMapper.countAll() > 0 && modelPresetMapper.countDefault() == 0) {
      ModelPresetEntity fallback = modelPresetMapper.findFirstByCreatedAtDesc();
      if (fallback != null) {
        modelPresetMapper.setDefault(fallback.getId());
      }
    }
    log.info(
        "模型预设已删除：presetId={}, deletedDefault={}, removedFromFallbackPresets={}",
        presetId,
        deletingDefault,
        affectedNames
    );
    return List.copyOf(affectedNames);
  }

  private ModelPresetEntity requirePreset(String presetId) {
    ModelPresetEntity preset = modelPresetMapper.findById(presetId);
    if (preset == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "预设不存在。");
    }
    return preset;
  }

  /**
   * 校验请求中的 Fallback 预设 ID 列表：必须存在、非自引用、去重、已配置完成且不构成循环引用。
   */
  private List<String> validateFallbackReferences(String ownerId, List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String fallbackId : requested) {
      if (fallbackId == null || fallbackId.isBlank()) {
        continue;
      }
      if (ownerId != null && ownerId.equals(fallbackId)) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "预设不能引用自身作为 Fallback。");
      }
      if (!seen.add(fallbackId)) {
        continue;
      }
      ModelPresetEntity fallbackPreset = modelPresetMapper.findById(fallbackId);
      if (fallbackPreset == null) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "Fallback 预设不存在。");
      }
      if (!normalizer.isConfigured(fallbackPreset)) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "Fallback 预设“" + fallbackPreset.getName() + "”尚未配置完成，请先补全配置。"
        );
      }
      if (ownerId != null && reachesOwner(ownerId, fallbackPreset, new HashSet<>())) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "Fallback 配置不能形成循环引用。");
      }
      result.add(fallbackId);
    }
    return List.copyOf(result);
  }

  /**
   * 沿存储的 Fallback 引用图递归，判断是否能够回到 owner 预设（用于成环检测）。
   */
  private boolean reachesOwner(String ownerId, ModelPresetEntity current, Set<String> visited) {
    if (!visited.add(current.getId())) {
      return false;
    }
    for (String nextId : ModelPresetFallbacks.parse(current.getFallbackPresetIds())) {
      if (ownerId.equals(nextId)) {
        return true;
      }
      ModelPresetEntity next = modelPresetMapper.findById(nextId);
      if (next != null && reachesOwner(ownerId, next, visited)) {
        return true;
      }
    }
    return false;
  }

  private List<ReferencedPresetInstance> referencedInstances(String presetId) {
    List<InstanceEntity> instances = instanceAggregateMapper.listAll();
    if (instances.isEmpty()) {
      return List.of();
    }

    Map<String, InstanceEntity> instancesById = new LinkedHashMap<>();
    for (InstanceEntity instance : instances) {
      instancesById.put(instance.getId(), instance);
    }

    Map<String, List<Integer>> indexesByInstanceId = new LinkedHashMap<>();
    for (InstanceModelEntity model : instanceAggregateMapper.listModelsByInstanceIds(new ArrayList<>(instancesById.keySet()))) {
      if (!presetId.equals(defaultString(model.getPresetId()))) {
        continue;
      }
      indexesByInstanceId
          .computeIfAbsent(model.getInstanceId(), ignored -> new ArrayList<>())
          .add(model.getSortOrder());
    }

    List<ReferencedPresetInstance> result = new ArrayList<>();
    for (Map.Entry<String, List<Integer>> entry : indexesByInstanceId.entrySet()) {
      InstanceEntity instance = instancesById.get(entry.getKey());
      if (instance != null) {
        result.add(new ReferencedPresetInstance(instance, List.copyOf(entry.getValue())));
      }
    }
    return result;
  }

  /**
   * 保存并同步：把该预设的完整模型链（主模型 + 一级 Fallback）覆盖到全部实例。
   * 运行中实例在事务提交后重启（重新 provisioning）；停止实例重写配置文件。
   */
  private ModelPresetSyncResult syncAllInstancesWithChain(ModelPresetEntity owner) {
    List<InstanceEntity> instances = instanceAggregateMapper.listAll();
    if (instances.isEmpty()) {
      return new ModelPresetSyncResult(true, 0, List.of(), List.of());
    }

    List<ModelChainEntry> chain = materializeChain(owner);
    log.info(
        "开始同步模型预设链到全部实例：presetId={}, affectedInstances={}, chainSize={}",
        owner.getId(),
        instances.size(),
        chain.size()
    );

    List<String> updatedInstanceIds = new ArrayList<>();
    List<String> restartedInstanceIds = new ArrayList<>();
    List<Runnable> afterCommitTasks = new ArrayList<>();
    String now = Instant.now().toString();

    for (InstanceEntity instance : instances) {
      List<InstanceModelEntity> nextModels = buildInstanceModels(instance.getId(), chain);
      instanceMutationMapper.deleteModelsForInstance(instance.getId());
      for (InstanceModelEntity nextModel : nextModels) {
        instanceMutationMapper.insertModel(nextModel);
      }
      resetModelAuth(instance.getId(), now);
      updatedInstanceIds.add(instance.getId());

      RuntimeState runtimeState = openClawRuntime.inspectInstance(instance);
      if (runtimeState.running()) {
        restartedInstanceIds.add(instance.getId());
        afterCommitTasks.add(() -> provisioningService.startProvisioning(instance.getId()));
      } else {
        String runtimeStatus = defaultString(runtimeState.status());
        if (!runtimeStatus.isBlank()) {
          instanceMutationMapper.updateInstanceStatus(instance.getId(), runtimeStatus, now);
          instance.setStatus(runtimeStatus);
        }
        afterCommitTasks.add(() -> fileService.writeInstanceFiles(instance, nextModels));
      }
    }

    runAfterCommit(() -> afterCommitTasks.forEach(Runnable::run));
    return new ModelPresetSyncResult(
        true,
        instances.size(),
        List.copyOf(updatedInstanceIds),
        List.copyOf(restartedInstanceIds)
    );
  }

  /**
   * 物化完整模型链：主预设与每个 Fallback 预设均做运行时可用性校验，返回按优先级排序的链。
   */
  private List<ModelChainEntry> materializeChain(ModelPresetEntity owner) {
    List<ModelChainEntry> chain = new ArrayList<>();
    NormalizedModelSelection primary = normalizer.normalizePreset(owner);
    normalizer.validateRuntimeUsable(primary, owner.getName());
    chain.add(new ModelChainEntry(primary, owner.getId()));
    for (String fallbackId : ModelPresetFallbacks.parse(owner.getFallbackPresetIds())) {
      ModelPresetEntity fallbackPreset = modelPresetMapper.findById(fallbackId);
      if (fallbackPreset == null) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "Fallback 预设已不存在，请先调整预设配置后再同步。"
        );
      }
      NormalizedModelSelection fallbackModel = normalizer.normalizePreset(fallbackPreset);
      normalizer.validateRuntimeUsable(fallbackModel, fallbackPreset.getName());
      chain.add(new ModelChainEntry(fallbackModel, fallbackPreset.getId()));
    }
    return List.copyOf(chain);
  }

  private static List<InstanceModelEntity> buildInstanceModels(
      String instanceId,
      List<ModelChainEntry> chain
  ) {
    List<InstanceModelEntity> models = new ArrayList<>();
    int sortOrder = 0;
    for (ModelChainEntry entry : chain) {
      models.add(toInstanceModel(instanceId, sortOrder, entry.presetId(), entry.model()));
      sortOrder++;
    }
    return models;
  }

  private void resetModelAuth(String instanceId, String updatedAt) {
    InstanceModelAuthEntity auth = new InstanceModelAuthEntity();
    auth.setInstanceId(instanceId);
    auth.setStatus("idle");
    auth.setMessage("");
    auth.setOutputSnippet("");
    auth.setAuthUrl("");
    auth.setPromptLabel("");
    auth.setNeedsInput(false);
    auth.setUpdatedAt(updatedAt);
    instanceMutationMapper.updateModelAuth(auth);
  }

  private static InstanceModelEntity toInstanceModel(
      String instanceId,
      int sortOrder,
      String presetId,
      NormalizedModelSelection model
  ) {
    InstanceModelEntity entity = new InstanceModelEntity();
    entity.setInstanceId(instanceId);
    entity.setSortOrder(sortOrder);
    entity.setPresetId(presetId);
    entity.setProviderKey(model.providerKey());
    entity.setProviderId(model.providerId());
    entity.setModelId(model.modelId());
    entity.setApiMode(model.apiMode());
    entity.setAuthType(model.authType());
    entity.setAuthProviderId(model.authProviderId());
    entity.setAuthMethodId(model.authMethodId());
    entity.setBaseUrl(model.baseUrl());
    entity.setApiKey(model.apiKey());
    entity.setProviderConfig(model.providerConfigJson());
    entity.setExtra(model.extraJson());
    entity.setContextWindow(model.contextWindow());
    entity.setMaxTokens(model.maxTokens());
    return entity;
  }

  private static void runAfterCommit(Runnable task) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          task.run();
        }
      });
      return;
    }
    task.run();
  }

  private static void applyModel(ModelPresetEntity preset, NormalizedModelSelection model) {
    preset.setProviderKey(model.providerKey());
    preset.setProviderId(model.providerId());
    preset.setModelId(model.modelId());
    preset.setApiMode(model.apiMode());
    preset.setAuthType(model.authType());
    preset.setAuthProviderId(model.authProviderId());
    preset.setAuthMethodId(model.authMethodId());
    preset.setBaseUrl(model.baseUrl());
    preset.setApiKey(model.apiKey());
    preset.setProviderConfig(model.providerConfigJson());
    preset.setExtra(model.extraJson());
    preset.setContextWindow(model.contextWindow());
    preset.setMaxTokens(model.maxTokens());
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  private record ReferencedPresetInstance(
      InstanceEntity instance,
      List<Integer> modelIndexes
  ) {
    PublicModelPresetUsageInstance publicUsage() {
      return new PublicModelPresetUsageInstance(
          instance.getId(),
          instance.getName(),
          instance.getStatus(),
          modelIndexes
      );
    }
  }
}
