package com.clawbotforall.instance;

import com.clawbotforall.model.ModelPresetEntity;
import com.clawbotforall.model.ModelPresetMapper;
import com.clawbotforall.model.ModelPresetNormalizer;
import com.clawbotforall.model.NormalizedModelSelection;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理实例的主模型和备用模型配置。
 */
@Service
public class InstanceModelService {

  private static final Logger log = LoggerFactory.getLogger(InstanceModelService.class);

  private final InstanceCommandService commandService;
  private final InstanceMutationMapper mutationMapper;
  private final ModelPresetMapper modelPresetMapper;
  private final ModelPresetNormalizer modelPresetNormalizer;
  private final InstanceFileService fileService;
  private final OpenClawRuntime openClawRuntime;

  public InstanceModelService(
      InstanceCommandService commandService,
      InstanceMutationMapper mutationMapper,
      ModelPresetMapper modelPresetMapper,
      ModelPresetNormalizer modelPresetNormalizer,
      InstanceFileService fileService,
      OpenClawRuntime openClawRuntime
  ) {
    this.commandService = commandService;
    this.mutationMapper = mutationMapper;
    this.modelPresetMapper = modelPresetMapper;
    this.modelPresetNormalizer = modelPresetNormalizer;
    this.fileService = fileService;
    this.openClawRuntime = openClawRuntime;
  }

  /**
   * 替换实例主模型配置。
   */

  @Transactional
  public InstanceModelUpdateResult updatePrimary(
      InstanceEntity instance,
      Map<String, Object> payload
  ) {
    List<InstanceModelEntity> current = commandService.listModels(instance.getId());
    if (current.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请先保存模型配置。");
    }

    ResolvedInstanceModel nextPrimary = resolveModel(payload, current.getFirst());
    List<InstanceModelEntity> nextModels = new ArrayList<>();
    nextModels.add(toEntity(instance.getId(), 0, nextPrimary.model(), nextPrimary.presetId()));
    for (int index = 1; index < current.size(); index += 1) {
      nextModels.add(copyWithSortOrder(current.get(index), index));
    }
    return persist(instance, nextModels);
  }

  /**
   * 向实例模型链添加模型。
   */

  @Transactional
  public InstanceModelUpdateResult addModel(
      InstanceEntity instance,
      Map<String, Object> payload
  ) {
    List<InstanceModelEntity> current = new ArrayList<>(commandService.listModels(instance.getId()));
    ResolvedInstanceModel model = resolveModel(payload, null);
    InstanceModelEntity next = toEntity(instance.getId(), 0, model.model(), model.presetId());
    boolean makePrimary = parseBooleanFlag(payload == null ? null : payload.get("makePrimary"), false) || current.isEmpty();

    List<InstanceModelEntity> nextModels = new ArrayList<>();
    if (makePrimary) {
      nextModels.add(next);
      nextModels.addAll(current);
    } else {
      nextModels.addAll(current);
      nextModels.add(next);
    }
    return persist(instance, reindex(instance.getId(), nextModels));
  }

  /**
   * 重排实例模型链。
   */

  @Transactional
  public InstanceModelUpdateResult reorder(
      InstanceEntity instance,
      Map<String, Object> payload
  ) {
    List<InstanceModelEntity> current = new ArrayList<>(commandService.listModels(instance.getId()));
    int index = parseIndex(payload == null ? null : payload.get("index"), current.size());
    String direction = stringValue(payload == null ? null : payload.get("direction")).toLowerCase(Locale.ROOT);
    int delta = direction.equals("up") ? -1 : direction.equals("down") ? 1 : 0;
    int targetIndex = index + delta;
    if (delta == 0 || targetIndex < 0 || targetIndex >= current.size()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "无法继续移动当前模型。");
    }

    InstanceModelEntity selected = current.remove(index);
    current.add(targetIndex, selected);
    return persist(instance, reindex(instance.getId(), current));
  }

  @Transactional
  public InstanceModelUpdateResult setPrimary(
      InstanceEntity instance,
      int index
  ) {
    List<InstanceModelEntity> current = new ArrayList<>(commandService.listModels(instance.getId()));
    validateIndex(index, current.size());
    if (current.size() <= 1) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要保留一个默认模型。");
    }

    InstanceModelEntity selected = current.remove(index);
    current.add(0, selected);
    return persist(instance, reindex(instance.getId(), current));
  }

  /**
   * 从实例模型链中删除备用模型。
   */

  @Transactional
  public InstanceModelUpdateResult deleteModel(
      InstanceEntity instance,
      int index
  ) {
    List<InstanceModelEntity> current = new ArrayList<>(commandService.listModels(instance.getId()));
    validateIndex(index, current.size());
    if (current.size() <= 1) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要保留一个默认模型。");
    }

    current.remove(index);
    return persist(instance, reindex(instance.getId(), current));
  }

  private InstanceModelUpdateResult persist(
      InstanceEntity instance,
      List<InstanceModelEntity> models
  ) {
    if (models.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要保留一个默认模型。");
    }

    mutationMapper.deleteModelsForInstance(instance.getId());
    for (InstanceModelEntity model : models) {
      mutationMapper.insertModel(model);
    }
    resetModelAuth(instance.getId());

    RuntimeState runtimeState = openClawRuntime.inspectInstance(instance);
    boolean restartRequired = runtimeState.running();
    if (!restartRequired) {
      fileService.writeInstanceFiles(instance, models);
      String status = runtimeState.status() == null || runtimeState.status().isBlank()
          ? instance.getStatus()
          : runtimeState.status();
      commandService.updateInstanceStatus(instance.getId(), status);
      instance.setStatus(status);
    } else {
      commandService.updateInstanceStatus(instance.getId(), "running");
      instance.setStatus("running");
    }
    instance.setUpdatedAt(Instant.now().toString());
    log.info(
        "实例模型配置已更新：instanceId={}, modelCount={}, restartRequired={}",
        instance.getId(),
        models.size(),
        restartRequired
    );
    return new InstanceModelUpdateResult(instance, restartRequired);
  }

  private void resetModelAuth(String instanceId) {
    InstanceModelAuthEntity auth = new InstanceModelAuthEntity();
    auth.setInstanceId(instanceId);
    auth.setStatus("idle");
    auth.setMessage("");
    auth.setOutputSnippet("");
    auth.setAuthUrl("");
    auth.setPromptLabel("");
    auth.setNeedsInput(false);
    auth.setUpdatedAt(Instant.now().toString());
    mutationMapper.updateModelAuth(auth);
  }

  private ResolvedInstanceModel resolveModel(
      Map<String, Object> payload,
      InstanceModelEntity existing
  ) {
    Map<String, Object> body = payload == null ? Map.of() : payload;
    String presetId = stringValue(body.get("presetId"));
    NormalizedModelSelection model;
    if (!presetId.isBlank()) {
      ModelPresetEntity preset = modelPresetMapper.findById(presetId);
      if (preset == null) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "所选模型预设不存在。");
      }
      model = modelPresetNormalizer.normalizePreset(preset);
      modelPresetNormalizer.validateRuntimeUsable(model, preset.getName());
      return new ResolvedInstanceModel(model, presetId);
    }

    model = modelPresetNormalizer.normalizePayloadWithExistingSelection(
        body,
        existing == null ? null : normalized(existing)
    );
    modelPresetNormalizer.validateRuntimeUsable(model, "");
      return new ResolvedInstanceModel(model, null);
  }

  private static NormalizedModelSelection normalized(InstanceModelEntity model) {
    return new NormalizedModelSelection(
        model.getProviderKey(),
        model.getProviderId(),
        model.getModelId(),
        model.getApiMode(),
        model.getAuthType(),
        model.getAuthProviderId(),
        model.getAuthMethodId(),
        model.getBaseUrl(),
        model.getApiKey(),
        model.getProviderConfig(),
        model.getExtra()
    );
  }

  private static List<InstanceModelEntity> reindex(
      String instanceId,
      List<InstanceModelEntity> models
  ) {
    List<InstanceModelEntity> result = new ArrayList<>();
    for (int index = 0; index < models.size(); index += 1) {
      result.add(copyWithSortOrder(models.get(index), index, instanceId));
    }
    return result;
  }

  private static InstanceModelEntity toEntity(
      String instanceId,
      int sortOrder,
      NormalizedModelSelection model,
      String presetId
  ) {
    InstanceModelEntity entity = new InstanceModelEntity();
    entity.setInstanceId(instanceId);
    entity.setSortOrder(sortOrder);
    entity.setPresetId(normalizedPresetId(presetId));
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

  private static InstanceModelEntity copyWithSortOrder(
      InstanceModelEntity source,
      int sortOrder
  ) {
    return copyWithSortOrder(source, sortOrder, source.getInstanceId());
  }

  private static InstanceModelEntity copyWithSortOrder(
      InstanceModelEntity source,
      int sortOrder,
      String instanceId
  ) {
    InstanceModelEntity copy = new InstanceModelEntity();
    copy.setInstanceId(instanceId);
    copy.setSortOrder(sortOrder);
    copy.setPresetId(source.getPresetId());
    copy.setProviderKey(source.getProviderKey());
    copy.setProviderId(source.getProviderId());
    copy.setModelId(source.getModelId());
    copy.setApiMode(source.getApiMode());
    copy.setAuthType(source.getAuthType());
    copy.setAuthProviderId(source.getAuthProviderId());
    copy.setAuthMethodId(source.getAuthMethodId());
    copy.setBaseUrl(source.getBaseUrl());
    copy.setApiKey(source.getApiKey());
    copy.setProviderConfig(source.getProviderConfig());
    copy.setExtra(source.getExtra());
    return copy;
  }

  private static int parseIndex(Object raw, int size) {
    int index;
    try {
      index = Integer.parseInt(stringValue(raw));
    } catch (NumberFormatException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "模型索引无效。");
    }
    validateIndex(index, size);
    return index;
  }

  private static void validateIndex(int index, int size) {
    if (index < 0 || index >= size) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "模型索引无效。");
    }
  }

  private static boolean parseBooleanFlag(Object value, boolean fallback) {
    if (value == null || stringValue(value).isBlank()) {
      return fallback;
    }
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    String normalized = stringValue(value).toLowerCase(Locale.ROOT);
    return normalized.equals("1")
        || normalized.equals("true")
        || normalized.equals("yes")
        || normalized.equals("on");
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String normalizedPresetId(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record ResolvedInstanceModel(
      NormalizedModelSelection model,
      String presetId
  ) {}
}
