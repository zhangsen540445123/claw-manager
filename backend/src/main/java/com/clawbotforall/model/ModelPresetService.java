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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 管理模型预设创建、更新、删除和默认选择。
 */
@Service
public class ModelPresetService {

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
        .map(preset -> PublicModelPreset.from(preset, normalizer.isConfigured(preset)))
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

    ModelPresetEntity preset = new ModelPresetEntity();
    preset.setId("preset_" + Long.toString(System.currentTimeMillis(), 36)
        + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6));
    preset.setName(name);
    preset.setDefault(isDefault);
    applyModel(preset, model);
    preset.setCreatedAt(Instant.now().toString());

    if (preset.isDefault()) {
      modelPresetMapper.clearDefault();
    }
    modelPresetMapper.insert(preset);
    return PublicModelPreset.from(preset, normalizer.isConfigured(preset));
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

    preset.setName(name);
    preset.setDefault(isDefault);
    applyModel(preset, model);
    if (preset.isDefault()) {
      modelPresetMapper.clearDefault();
    }
    modelPresetMapper.update(preset);

    boolean shouldSyncReferencedInstances = normalizer.parseBooleanFlag(
        payload == null ? null : payload.get("syncReferencedInstances"),
        false
    );
    List<ReferencedPresetInstance> references = referencedInstances(presetId);
    if (shouldSyncReferencedInstances && !references.isEmpty()) {
      normalizer.validateRuntimeUsable(model, preset.getName());
    }
    ModelPresetSyncResult sync = shouldSyncReferencedInstances
        ? syncReferencedInstances(preset, model, references)
        : ModelPresetSyncResult.notRequested(references.size());
    return new ModelPresetUpdateResult(
        PublicModelPreset.from(preset, normalizer.isConfigured(preset)),
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
  }

  /**
   * 在不会破坏预设目录时删除预设。
   */

  @Transactional
  public void deletePreset(String presetId) {
    ModelPresetEntity preset = modelPresetMapper.findById(presetId);
    if (preset == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "预设不存在。");
    }

    boolean deletingDefault = preset.isDefault();
    modelPresetMapper.delete(presetId);
    if (deletingDefault && modelPresetMapper.countAll() > 0 && modelPresetMapper.countDefault() == 0) {
      ModelPresetEntity fallback = modelPresetMapper.findFirstByCreatedAtDesc();
      if (fallback != null) {
        modelPresetMapper.setDefault(fallback.getId());
      }
    }
  }

  private ModelPresetEntity requirePreset(String presetId) {
    ModelPresetEntity preset = modelPresetMapper.findById(presetId);
    if (preset == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "预设不存在。");
    }
    return preset;
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

  private ModelPresetSyncResult syncReferencedInstances(
      ModelPresetEntity preset,
      NormalizedModelSelection model,
      List<ReferencedPresetInstance> references
  ) {
    if (references.isEmpty()) {
      return new ModelPresetSyncResult(true, 0, List.of(), List.of());
    }

    List<String> instanceIds = references.stream()
        .map(reference -> reference.instance().getId())
        .toList();
    Map<String, List<InstanceModelEntity>> modelsByInstanceId = new LinkedHashMap<>();
    for (InstanceModelEntity instanceModel : instanceAggregateMapper.listModelsByInstanceIds(instanceIds)) {
      modelsByInstanceId
          .computeIfAbsent(instanceModel.getInstanceId(), ignored -> new ArrayList<>())
          .add(instanceModel);
    }

    List<String> updatedInstanceIds = new ArrayList<>();
    List<String> restartedInstanceIds = new ArrayList<>();
    List<Runnable> afterCommitTasks = new ArrayList<>();
    String now = Instant.now().toString();

    for (ReferencedPresetInstance reference : references) {
      InstanceEntity instance = reference.instance();
      List<InstanceModelEntity> currentModels = modelsByInstanceId.getOrDefault(instance.getId(), List.of());
      List<InstanceModelEntity> nextModels = syncModelsForPreset(preset.getId(), model, currentModels);
      if (nextModels.isEmpty()) {
        continue;
      }

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
        references.size(),
        List.copyOf(updatedInstanceIds),
        List.copyOf(restartedInstanceIds)
    );
  }

  private List<InstanceModelEntity> syncModelsForPreset(
      String presetId,
      NormalizedModelSelection model,
      List<InstanceModelEntity> currentModels
  ) {
    List<InstanceModelEntity> nextModels = new ArrayList<>();
    for (InstanceModelEntity currentModel : currentModels) {
      if (presetId.equals(defaultString(currentModel.getPresetId()))) {
        nextModels.add(toInstanceModel(currentModel.getInstanceId(), currentModel.getSortOrder(), presetId, model));
      } else {
        nextModels.add(currentModel);
      }
    }
    return nextModels;
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
