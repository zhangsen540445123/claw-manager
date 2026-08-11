package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

  @Test
  void ensureUserAgentRequiresStrictSuccessfulRpcPayload() {
    completeExecWith("{\"agentId\":\"user_1\",\"persisted\":true,\"runtimeApplied\":true,\"wechatBindingCreated\":true}");

    service.ensureUserAgent(instance(), "user_1", "wx_1", "bot-a", "peer-a");

    String script = capturedCommand.get().get(3);
    assertThat(script).contains("claw-manager-api.ensure-user-agent");
  }

  @Test
  void ensureUserAgentRejectsMissingWechatBindingResultEvenWhenProcessExitsZero() {
    completeExecWith("{\"persisted\":true,\"runtimeApplied\":true}");

    assertThatThrownBy(() -> service.ensureUserAgent(instance(), "user_1", "wx_1", "bot-a", "peer-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wechatBindingCreated");
  }

  @Test
  void ensureApiBindingRejectsFalseRuntimeAppliedEvenWhenProcessExitsZero() {
    completeExecWith("{\"persisted\":true,\"runtimeApplied\":false,\"apiBindingCreated\":false}");

    assertThatThrownBy(() -> service.ensureApiBinding(instance(), "user_1", "wx_1", "api:sender-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runtimeApplied");
  }

  @Test
  void replaceUserAgentReturnsDisplacedAgentsAndRejectsConflicts() {
    completeExecWith("{\"persisted\":true,\"runtimeApplied\":true,\"bindingCreated\":true,\"displacedAgentIds\":[\"user_old\"],\"conflictingBindings\":[]}");

    OpenClawGatewayRpcService.ReplaceUserAgentResult result = service.replaceUserAgent(
        instance(), "user_new", "wx_1", "bot-new", "peer-a", "user_old", List.of("api:old"));

    assertThat(result.displacedAgentIds()).containsExactly("user_old");
    assertThat(capturedCommand.get().get(3)).contains("claw-manager-api.replace-user-agent");
  }

  @Test
  void replaceUserAgentReportsConflictingBindingsWithoutTreatingThemAsSuccess() {
    completeExecWith("{\"persisted\":false,\"runtimeApplied\":false,\"bindingCreated\":false,\"displacedAgentIds\":[],\"conflictingBindings\":[{\"channel\":\"other\"}]}");

    OpenClawGatewayRpcService.ReplaceUserAgentResult result = service.replaceUserAgent(
        instance(), "user_new", "wx_1", "bot-new", "peer-a", "user_old", List.of());

    assertThat(result.success()).isFalse();
    assertThat(result.conflictingBindings()).hasSize(1);
  }

  @Test
  void replaceUserAgentTreatsMissingOptionalArraysAsEmpty() {
    completeExecWith("{\"persisted\":true,\"runtimeApplied\":true,\"bindingCreated\":true}");

    OpenClawGatewayRpcService.ReplaceUserAgentResult result = service.replaceUserAgent(
        instance(), "user_new", "wx_1", "bot-new", "peer-a", "user_old", List.of());

    assertThat(result.displacedAgentIds()).isEmpty();
    assertThat(result.conflictingBindings()).isEmpty();
    assertThat(result.success()).isTrue();
  }

  @Test
  void replaceUserAgentTreatsNullOptionalArraysAsEmpty() {
    completeExecWith("{\"persisted\":true,\"runtimeApplied\":true,\"bindingCreated\":true,\"displacedAgentIds\":null,\"conflictingBindings\":null}");

    OpenClawGatewayRpcService.ReplaceUserAgentResult result = service.replaceUserAgent(
        instance(), "user_new", "wx_1", "bot-new", "peer-a", "user_old", List.of());

    assertThat(result.displacedAgentIds()).isEmpty();
    assertThat(result.conflictingBindings()).isEmpty();
    assertThat(result.success()).isTrue();
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
