package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.model.ModelPresetEntity;
import com.clawbotforall.model.ModelPresetMapper;
import com.clawbotforall.model.ModelPresetNormalizer;
import com.clawbotforall.model.NormalizedModelSelection;
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
class InstanceCommandServiceTest {

  @Mock
  InstanceMutationMapper instanceMutationMapper;

  @Mock
  InstanceAggregateMapper instanceAggregateMapper;

  @Mock
  ModelPresetMapper modelPresetMapper;

  @Mock
  ModelPresetNormalizer modelPresetNormalizer;

  @Mock
  InstancePortAllocator portAllocator;

  @Mock
  InstanceRecordFactory recordFactory;

  InstanceCommandService service;

  @BeforeEach
  void setUp() {
    service = new InstanceCommandService(
        instanceMutationMapper,
        instanceAggregateMapper,
        modelPresetMapper,
        modelPresetNormalizer,
        portAllocator,
        recordFactory
    );
    lenient().when(recordFactory.sanitizeName(any())).thenAnswer(invocation -> {
      Object value = invocation.getArgument(0);
      return value == null ? "" : String.valueOf(value).trim();
    });
  }

  @Test
  void creatingInstanceWithFallbackPresetSeedsFullChainInPriorityOrder() {
    ModelPresetEntity primary = preset("preset_1", "[\"preset_2\",\"preset_3\"]");
    ModelPresetEntity fallbackTwo = preset("preset_2", null);
    ModelPresetEntity fallbackThree = preset("preset_3", null);
    when(modelPresetMapper.findById("preset_1")).thenReturn(primary);
    when(modelPresetMapper.findById("preset_2")).thenReturn(fallbackTwo);
    when(modelPresetMapper.findById("preset_3")).thenReturn(fallbackThree);
    when(modelPresetNormalizer.normalizePreset(primary)).thenReturn(selection("openai", "gpt-6"));
    when(modelPresetNormalizer.normalizePreset(fallbackTwo)).thenReturn(selection("anthropic", "claude-4"));
    when(modelPresetNormalizer.normalizePreset(fallbackThree)).thenReturn(selection("google", "gemini-3"));
    when(portAllocator.findAvailablePort()).thenReturn(19001);
    when(recordFactory.create("测试实例", selection("openai", "gpt-6"), "preset_1", 19001))
        .thenReturn(draft(primaryModel("inst-1", "preset_1", "gpt-6")));

    service.createInstance(Map.of("name", "测试实例", "presetId", "preset_1"));

    verify(recordFactory).create("测试实例", selection("openai", "gpt-6"), "preset_1", 19001);
    verify(instanceMutationMapper).insertInstance(any());
    verify(instanceMutationMapper).insertProvisioning(any());
    verify(instanceMutationMapper).insertModelAuth(any());
    ArgumentCaptor<InstanceModelEntity> captor = ArgumentCaptor.forClass(InstanceModelEntity.class);
    verify(instanceMutationMapper, times(3)).insertModel(captor.capture());
    assertThat(captor.getAllValues()).extracting(InstanceModelEntity::getSortOrder)
        .containsExactly(0, 1, 2);
    assertThat(captor.getAllValues()).extracting(InstanceModelEntity::getPresetId)
        .containsExactly("preset_1", "preset_2", "preset_3");
    assertThat(captor.getAllValues()).extracting(InstanceModelEntity::getModelId)
        .containsExactly("gpt-6", "claude-4", "gemini-3");
  }

  @Test
  void creatingInstanceWithoutFallbackWritesOnlyPrimaryModelRow() {
    ModelPresetEntity primary = preset("preset_1", null);
    when(modelPresetMapper.findById("preset_1")).thenReturn(primary);
    when(modelPresetNormalizer.normalizePreset(primary)).thenReturn(selection("openai", "gpt-6"));
    when(portAllocator.findAvailablePort()).thenReturn(19001);
    when(recordFactory.create("测试实例", selection("openai", "gpt-6"), "preset_1", 19001))
        .thenReturn(draft(primaryModel("inst-1", "preset_1", "gpt-6")));

    service.createInstance(Map.of("name", "测试实例", "presetId", "preset_1"));

    verify(instanceMutationMapper, times(1)).insertModel(any());
    verify(instanceMutationMapper).insertInstance(any());
  }

  @Test
  void creatingInstanceRejectsMissingFallbackPreset() {
    ModelPresetEntity primary = preset("preset_1", "[\"preset_missing\"]");
    when(modelPresetMapper.findById("preset_1")).thenReturn(primary);
    when(modelPresetMapper.findById("preset_missing")).thenReturn(null);
    when(modelPresetNormalizer.normalizePreset(primary)).thenReturn(selection("openai", "gpt-6"));

    assertThatThrownBy(() -> service.createInstance(
        Map.of("name", "测试实例", "presetId", "preset_1")
    ))
        .isInstanceOf(ApiException.class)
        .hasMessage("所选预设的 Fallback 模型预设已不存在，请先调整预设配置。")
        .extracting("status")
        .isEqualTo(HttpStatus.BAD_REQUEST);

    verify(instanceMutationMapper, times(0)).insertModel(any());
  }

  private static InstanceCreationDraft draft(InstanceModelEntity primaryModel) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId(primaryModel.getInstanceId());
    instance.setName("测试实例");
    instance.setStatus("stopped");
    return new InstanceCreationDraft(
        instance,
        primaryModel,
        new InstanceProvisioningEntity(),
        new InstanceModelAuthEntity()
    );
  }

  private static InstanceModelEntity primaryModel(String instanceId, String presetId, String modelId) {
    InstanceModelEntity model = new InstanceModelEntity();
    model.setInstanceId(instanceId);
    model.setSortOrder(0);
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
    model.setContextWindow(200_000);
    model.setMaxTokens(20_000);
    return model;
  }

  private static ModelPresetEntity preset(String id, String fallbackPresetIds) {
    ModelPresetEntity preset = new ModelPresetEntity();
    preset.setId(id);
    preset.setName(id);
    preset.setDefault(false);
    preset.setProviderKey("custom-provider");
    preset.setProviderId("openai");
    preset.setModelId("gpt-6");
    preset.setApiMode("openai-responses");
    preset.setAuthType("custom_gateway");
    preset.setAuthProviderId("openai");
    preset.setBaseUrl("https://example.test/v1");
    preset.setApiKey("sk-test");
    preset.setFallbackPresetIds(fallbackPresetIds);
    preset.setContextWindow(200_000);
    preset.setMaxTokens(20_000);
    preset.setCreatedAt("2026-06-01T00:00:00Z");
    return preset;
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
        "{}",
        200_000,
        20_000
    );
  }
}
