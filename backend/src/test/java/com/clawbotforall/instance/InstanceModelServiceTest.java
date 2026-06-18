package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.model.ModelPresetMapper;
import com.clawbotforall.model.ModelPresetEntity;
import com.clawbotforall.model.ModelPresetNormalizer;
import com.clawbotforall.model.NormalizedModelSelection;
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

@ExtendWith(MockitoExtension.class)
class InstanceModelServiceTest {

  @Mock
  InstanceCommandService commandService;

  @Mock
  InstanceMutationMapper mutationMapper;

  @Mock
  ModelPresetMapper modelPresetMapper;

  @Mock
  ModelPresetNormalizer normalizer;

  @Mock
  InstanceFileService fileService;

  @Mock
  OpenClawRuntime runtime;

  InstanceModelService service;

  @BeforeEach
  void setUp() {
    service = new InstanceModelService(
        commandService,
        mutationMapper,
        modelPresetMapper,
        normalizer,
        fileService,
        runtime
    );
  }

  @Test
  void addModelPersistsReindexedChainAndWritesFilesWhenStopped() {
    InstanceEntity instance = instance("stopped");
    when(commandService.listModels("inst_1")).thenReturn(List.of(model("openai", "gpt-5.5", 0)));
    when(normalizer.normalizePayloadWithExistingSelection(any(), org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(selection("anthropic", "claude-test"));
    when(runtime.inspectInstance(instance)).thenReturn(RuntimeState.stopped());

    InstanceModelUpdateResult result = service.addModel(instance, Map.of(
        "providerId", "anthropic",
        "modelId", "claude-test",
        "apiMode", "anthropic-messages"
    ));

    ArgumentCaptor<InstanceModelEntity> captor = ArgumentCaptor.forClass(InstanceModelEntity.class);
    verify(mutationMapper).deleteModelsForInstance("inst_1");
    verify(mutationMapper, org.mockito.Mockito.times(2)).insertModel(captor.capture());
    verify(fileService).writeInstanceFiles(org.mockito.ArgumentMatchers.eq(instance), any());
    verify(commandService).updateInstanceStatus("inst_1", "stopped");
    assertThat(result.restartRequired()).isFalse();
    assertThat(captor.getAllValues()).extracting(InstanceModelEntity::getSortOrder)
        .containsExactly(0, 1);
    assertThat(captor.getAllValues()).extracting(InstanceModelEntity::getModelId)
        .containsExactly("gpt-5.5", "claude-test");
  }

  @Test
  void runningInstanceRequiresRestartAndSkipsFileWrite() {
    InstanceEntity instance = instance("running");
    when(commandService.listModels("inst_1")).thenReturn(List.of(
        model("openai", "gpt-5.5", 0),
        model("anthropic", "claude-test", 1)
    ));
    when(runtime.inspectInstance(instance)).thenReturn(new RuntimeState(true, "running", "now"));

    InstanceModelUpdateResult result = service.setPrimary(instance, 1);

    verify(commandService).updateInstanceStatus("inst_1", "running");
    verify(fileService, never()).writeInstanceFiles(any(), any());
    assertThat(result.restartRequired()).isTrue();
    assertThat(result.instance().getStatus()).isEqualTo("running");
  }

  @Test
  void rejectsDeletingOnlyModel() {
    InstanceEntity instance = instance("stopped");
    when(commandService.listModels("inst_1")).thenReturn(List.of(model("openai", "gpt-5.5", 0)));

    assertThatThrownBy(() -> service.deleteModel(instance, 0))
        .isInstanceOf(ApiException.class)
        .hasMessage("至少需要保留一个默认模型。");
  }

  @Test
  void rejectsInvalidReorderDirectionOrIndex() {
    InstanceEntity instance = instance("stopped");
    when(commandService.listModels("inst_1")).thenReturn(List.of(
        model("openai", "gpt-5.5", 0),
        model("anthropic", "claude-test", 1)
    ));

    assertThatThrownBy(() -> service.reorder(instance, Map.of("index", 0, "direction", "sideways")))
        .isInstanceOf(ApiException.class)
        .hasMessage("无法继续移动当前模型。");
  }

  @Test
  void rejectsMissingPresetWhenResolvingModel() {
    InstanceEntity instance = instance("stopped");
    when(commandService.listModels("inst_1")).thenReturn(List.of());

    assertThatThrownBy(() -> service.addModel(instance, Map.of("presetId", "missing")))
        .isInstanceOf(ApiException.class)
        .hasMessage("所选模型预设不存在。");
  }

  @Test
  void addModelFromPresetStoresPresetBinding() {
    InstanceEntity instance = instance("stopped");
    ModelPresetEntity preset = preset("preset_1");
    when(commandService.listModels("inst_1")).thenReturn(List.of());
    when(modelPresetMapper.findById("preset_1")).thenReturn(preset);
    when(normalizer.normalizePreset(preset)).thenReturn(selection("openai", "gpt-5.5"));
    when(runtime.inspectInstance(instance)).thenReturn(RuntimeState.stopped());

    service.addModel(instance, Map.of("presetId", "preset_1"));

    ArgumentCaptor<InstanceModelEntity> captor = ArgumentCaptor.forClass(InstanceModelEntity.class);
    verify(mutationMapper).insertModel(captor.capture());
    assertThat(captor.getValue().getPresetId()).isEqualTo("preset_1");
  }

  @Test
  void manualPrimaryUpdateClearsPresetBinding() {
    InstanceEntity instance = instance("stopped");
    InstanceModelEntity current = model("openai", "gpt-5.5", 0);
    current.setPresetId("preset_1");
    when(commandService.listModels("inst_1")).thenReturn(List.of(current));
    when(normalizer.normalizePayloadWithExistingSelection(any(), any()))
        .thenReturn(selection("anthropic", "claude-test"));
    when(runtime.inspectInstance(instance)).thenReturn(RuntimeState.stopped());

    service.updatePrimary(instance, Map.of(
        "providerId", "anthropic",
        "modelId", "claude-test",
        "apiMode", "anthropic-messages"
    ));

    ArgumentCaptor<InstanceModelEntity> captor = ArgumentCaptor.forClass(InstanceModelEntity.class);
    verify(mutationMapper).insertModel(captor.capture());
    assertThat(captor.getValue().getPresetId()).isNull();
  }

  private static InstanceEntity instance(String status) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("Instance");
    instance.setStatus(status);
    return instance;
  }

  private static InstanceModelEntity model(String providerId, String modelId, int sortOrder) {
    InstanceModelEntity model = new InstanceModelEntity();
    model.setInstanceId("inst_1");
    model.setSortOrder(sortOrder);
    model.setProviderKey("custom-provider");
    model.setProviderId(providerId);
    model.setModelId(modelId);
    model.setApiMode("openai-responses");
    model.setAuthType("custom_gateway");
    model.setAuthProviderId(providerId);
    model.setAuthMethodId("");
    model.setBaseUrl("https://example.test/v1");
    model.setApiKey("sk-test");
    model.setExtra("{}");
    return model;
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

  private static ModelPresetEntity preset(String id) {
    ModelPresetEntity preset = new ModelPresetEntity();
    preset.setId(id);
    preset.setName(id);
    return preset;
  }
}
