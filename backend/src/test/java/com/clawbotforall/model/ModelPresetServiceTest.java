package com.clawbotforall.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceModelEntity;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceProvisioningService;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ModelPresetServiceTest {

  @Mock
  ModelPresetMapper mapper;

  @Mock
  ModelPresetNormalizer normalizer;

  @Mock
  InstanceAggregateMapper instanceAggregateMapper;

  @Mock
  InstanceMutationMapper instanceMutationMapper;

  @Mock
  InstanceFileService fileService;

  @Mock
  OpenClawRuntime openClawRuntime;

  @Mock
  InstanceProvisioningService provisioningService;

  ModelPresetService service;

  @BeforeEach
  void setUp() {
    service = new ModelPresetService(
        mapper,
        normalizer,
        instanceAggregateMapper,
        instanceMutationMapper,
        fileService,
        openClawRuntime,
        provisioningService
    );
    lenient().when(normalizer.sanitizeName(any())).thenAnswer(invocation -> {
      Object value = invocation.getArgument(0);
      return value == null ? "" : String.valueOf(value).trim();
    });
  }

  @Test
  void createsFirstPresetAsDefaultAndClearsPreviousDefaultFlag() {
    when(mapper.countAll()).thenReturn(0);
    when(normalizer.normalizePayload(any(), org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(selection("openai", "gpt-5.5"));
    when(normalizer.parseBooleanFlag(org.mockito.ArgumentMatchers.isNull(), eq(true))).thenReturn(true);
    when(normalizer.isConfigured(any())).thenReturn(true);

    PublicModelPreset result = service.createPreset(Map.of("name", " GPT-5.5 "));

    ArgumentCaptor<ModelPresetEntity> captor = ArgumentCaptor.forClass(ModelPresetEntity.class);
    verify(mapper).clearDefault();
    verify(mapper).insert(captor.capture());
    assertThat(result.name()).isEqualTo("GPT-5.5");
    assertThat(result.isDefault()).isTrue();
    assertThat(captor.getValue().getModelId()).isEqualTo("gpt-5.5");
  }

  @Test
  void rejectsBlankPresetNameBeforeNormalizingModel() {
    assertThatThrownBy(() -> service.createPreset(Map.of("name", " ")))
        .isInstanceOf(ApiException.class)
        .hasMessage("预设名称不能为空。")
        .extracting("status")
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void updateMissingPresetReturnsNotFound() {
    assertThatThrownBy(() -> service.updatePreset("missing", Map.of("name", "GPT")))
        .isInstanceOf(ApiException.class)
        .extracting("status")
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void setDefaultRejectsMissingPreset() {
    assertThatThrownBy(() -> service.setDefault("missing"))
        .isInstanceOf(ApiException.class)
        .hasMessage("预设不存在。");
  }

  @Test
  void deletingDefaultPresetPromotesNewestFallback() {
    ModelPresetEntity deleting = preset("preset_1", true);
    ModelPresetEntity fallback = preset("preset_2", false);
    when(mapper.findById("preset_1")).thenReturn(deleting);
    when(mapper.countAll()).thenReturn(1);
    when(mapper.countDefault()).thenReturn(0);
    when(mapper.findFirstByCreatedAtDesc()).thenReturn(fallback);

    service.deletePreset("preset_1");

    verify(mapper).delete("preset_1");
    verify(mapper).setDefault("preset_2");
  }

  @Test
  void deletingMissingPresetReturnsNotFound() {
    assertThatThrownBy(() -> service.deletePreset("missing"))
        .isInstanceOf(ApiException.class)
        .extracting("status")
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void usageListsInstancesReferencingPreset() {
    ModelPresetEntity preset = preset("preset_1", false);
    InstanceEntity instance = instance("inst_1", "运行实例", "running");
    InstanceModelEntity model = instanceModel("inst_1", 0, "preset_1", "gpt-5.5");
    when(mapper.findById("preset_1")).thenReturn(preset);
    when(instanceAggregateMapper.listAll()).thenReturn(List.of(instance));
    when(instanceAggregateMapper.listModelsByInstanceIds(List.of("inst_1"))).thenReturn(List.of(model));

    PublicModelPresetUsage usage = service.usage("preset_1");

    assertThat(usage.instances()).hasSize(1);
    assertThat(usage.instances().getFirst().id()).isEqualTo("inst_1");
    assertThat(usage.instances().getFirst().modelIndexes()).containsExactly(0);
  }

  @Test
  void updatePresetCanSyncReferencedInstancesAndRestartRunningOnes() {
    ModelPresetEntity preset = preset("preset_1", false);
    InstanceEntity stopped = instance("inst_stopped", "停止实例", "stopped");
    InstanceEntity running = instance("inst_running", "运行实例", "running");
    InstanceModelEntity stoppedModel = instanceModel("inst_stopped", 0, "preset_1", "gpt-5.5");
    InstanceModelEntity runningModel = instanceModel("inst_running", 0, "preset_1", "gpt-5.5");
    Map<String, Object> payload = Map.of(
        "name", "GPT-6",
        "isDefault", false,
        "syncReferencedInstances", true
    );
    when(mapper.findById("preset_1")).thenReturn(preset);
    when(normalizer.normalizePayload(payload, preset)).thenReturn(selection("openai", "gpt-6"));
    when(normalizer.parseBooleanFlag(false, false)).thenReturn(false);
    when(normalizer.parseBooleanFlag(true, false)).thenReturn(true);
    when(normalizer.isConfigured(preset)).thenReturn(true);
    when(instanceAggregateMapper.listAll()).thenReturn(List.of(stopped, running));
    when(instanceAggregateMapper.listModelsByInstanceIds(List.of("inst_stopped", "inst_running")))
        .thenReturn(List.of(stoppedModel, runningModel));
    when(openClawRuntime.inspectInstance(stopped)).thenReturn(RuntimeState.stopped());
    when(openClawRuntime.inspectInstance(running)).thenReturn(new RuntimeState(true, "running", "now"));

    ModelPresetUpdateResult result = service.updatePreset("preset_1", payload);

    ArgumentCaptor<InstanceModelEntity> captor = ArgumentCaptor.forClass(InstanceModelEntity.class);
    verify(mapper).update(preset);
    verify(instanceMutationMapper, org.mockito.Mockito.times(2)).deleteModelsForInstance(any());
    verify(instanceMutationMapper, org.mockito.Mockito.times(2)).insertModel(captor.capture());
    verify(fileService).writeInstanceFiles(eq(stopped), any());
    verify(provisioningService).startProvisioning("inst_running");
    assertThat(captor.getAllValues()).extracting(InstanceModelEntity::getModelId)
        .containsOnly("gpt-6");
    assertThat(captor.getAllValues()).extracting(InstanceModelEntity::getPresetId)
        .containsOnly("preset_1");
    assertThat(result.sync().requested()).isTrue();
    assertThat(result.sync().affectedInstances()).isEqualTo(2);
    assertThat(result.sync().updatedInstanceIds()).containsExactly("inst_stopped", "inst_running");
    assertThat(result.sync().restartedInstanceIds()).containsExactly("inst_running");
  }

  private static NormalizedModelSelection selection(String providerId, String modelId) {
    return new NormalizedModelSelection(
        "custom-provider",
        providerId,
        modelId,
        "openai-responses",
        "custom_gateway",
        providerId,
        "",
        "https://example.test/v1",
        "sk-test",
        null,
        "{}"
    );
  }

  private static ModelPresetEntity preset(String id, boolean isDefault) {
    ModelPresetEntity preset = new ModelPresetEntity();
    preset.setId(id);
    preset.setName(id);
    preset.setDefault(isDefault);
    preset.setProviderKey("custom-provider");
    preset.setProviderId("openai");
    preset.setModelId("gpt-5.5");
    preset.setApiMode("openai-responses");
    preset.setAuthType("custom_gateway");
    preset.setAuthProviderId("openai");
    preset.setBaseUrl("https://example.test/v1");
    preset.setApiKey("sk-test");
    preset.setCreatedAt("2026-06-01T00:00:00Z");
    return preset;
  }

  private static InstanceEntity instance(String id, String name, String status) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId(id);
    instance.setName(name);
    instance.setStatus(status);
    return instance;
  }

  private static InstanceModelEntity instanceModel(
      String instanceId,
      int sortOrder,
      String presetId,
      String modelId
  ) {
    InstanceModelEntity model = new InstanceModelEntity();
    model.setInstanceId(instanceId);
    model.setSortOrder(sortOrder);
    model.setPresetId(presetId);
    model.setProviderKey("custom-provider");
    model.setProviderId("openai");
    model.setModelId(modelId);
    model.setApiMode("openai-responses");
    model.setAuthType("custom_gateway");
    model.setAuthProviderId("openai");
    model.setBaseUrl("https://example.test/v1");
    model.setApiKey("sk-test");
    model.setExtra("{}");
    return model;
  }
}
