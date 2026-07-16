package com.clawbotforall.image;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.trace.IntegrationTraceEventRequest;
import com.clawbotforall.trace.IntegrationTraceService;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ImageGenerationServiceTraceTest {
  @Test
  void recordsConfigurationFailureBeforeRejectingGeneration() {
    ImageGenerationSettingsProvider settings = mock(ImageGenerationSettingsProvider.class);
    IntegrationTraceService traces = mock(IntegrationTraceService.class);
    when(settings.current()).thenReturn(ImageGenerationSettings.disabled());
    ImageGenerationService service = new ImageGenerationService(
        settings, mock(InstanceFileService.class), new ObjectMapper(), traces);

    assertThatThrownBy(() -> service.generate(
        "inst_1", "测试图片", "1024x1024", "auto", "cmtrace_image123", "mbreq_1"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("尚未配置");

    ArgumentCaptor<IntegrationTraceEventRequest> captor = forClass(IntegrationTraceEventRequest.class);
    verify(traces).record(captor.capture(), org.mockito.ArgumentMatchers.eq("cmtrace_image123"));
    org.assertj.core.api.Assertions.assertThat(captor.getValue().stage()).isEqualTo("image.config.validated");
    org.assertj.core.api.Assertions.assertThat(captor.getValue().status()).isEqualTo("failed");
  }
}
