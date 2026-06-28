package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenClawGatewayRpcServiceTest {

  @Mock
  OpenClawRuntime openClawRuntime;

  OpenClawGatewayRpcService service;
  AtomicReference<List<String>> capturedCommand;

  @BeforeEach
  void setUp() {
    service = new OpenClawGatewayRpcService(openClawRuntime, new ObjectMapper());
    capturedCommand = new AtomicReference<>();
  }

  @Test
  void wechatChannelStartGatewayCallKeepsCoreChannelMethod() {
    completeExecWith("{\"started\":true}");

    service.startWechatChannel(instance(), List.of("wx_1"));

    String script = capturedCommand.get().get(3);
    assertThat(script).contains("const method = \"channels.start\"");
    assertThat(script).contains("const channel = \"openclaw-weixin\"");
    assertThat(script).doesNotContain("claw-manager-api.sendMessage");
  }

  @Test
  void apiChannelStartDoesNotCallGatewayRpcBecausePluginAutoStartsMonitor() {
    service.startApiChannel(instance());

    verifyNoInteractions(openClawRuntime);
  }

  @Test
  void apiChannelStartIsIdempotentNoop() {
    service.startApiChannel(instance());
    service.startApiChannel(instance());

    verifyNoInteractions(openClawRuntime);
  }

  private void completeExecWith(String output) {
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      List<String> command = invocation.getArgument(1, List.class);
      RuntimeExecListener listener = invocation.getArgument(4, RuntimeExecListener.class);
      capturedCommand.set(command);
      listener.onOutput(output);
      listener.onComplete(0);
      return null;
    }).when(openClawRuntime).startExec(any(), any(List.class), anyLong(), eq(Map.of()), any());
  }

  private InstanceEntity instance() {
    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");
    instance.setName("实例一");
    return instance;
  }
}
