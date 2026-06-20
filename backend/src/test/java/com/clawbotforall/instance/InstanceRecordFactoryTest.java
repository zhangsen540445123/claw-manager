package com.clawbotforall.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.model.NormalizedModelSelection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class InstanceRecordFactoryTest {

  private final InstanceRecordFactory factory = new InstanceRecordFactory(new ObjectMapper());

  @Test
  void createsNodeCompatibleInitialInstanceShape() {
    NormalizedModelSelection model = new NormalizedModelSelection(
        "custom-provider",
        "openai",
        "gpt-5.5",
        "openai-responses",
        "custom_gateway",
        "openai",
        "",
        "https://example.com/v1",
        "sk-test",
        null,
        "{}"
    );

    InstanceCreationDraft draft = factory.create(" 战神 实例 ", model, "preset_1", 19001);

    assertThat(draft.instance().getName()).isEqualTo(" 战神 实例 ");
    assertThat(draft.instance().getStatus()).isEqualTo("stopped");
    assertThat(draft.instance().getDashboardUrl()).isEqualTo("http://127.0.0.1:19001/");
    assertThat(draft.instance().getContainerName()).startsWith("clawbot-openclaw-");
    assertThat(draft.instance().getPluginsAllow()).isEqualTo("[]");
    assertThat(draft.model().getPresetId()).isEqualTo("preset_1");
    assertThat(draft.model().getProviderId()).isEqualTo("openai");
    assertThat(draft.provisioning().getStatus()).isEqualTo("running");
    assertThat(draft.provisioning().getPercent()).isEqualTo(5);
    assertThat(draft.provisioning().getStage()).isEqualTo("queued");
    assertThat(draft.modelAuth().getStatus()).isEqualTo("idle");
  }
}
