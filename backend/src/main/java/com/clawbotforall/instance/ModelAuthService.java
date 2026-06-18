package com.clawbotforall.instance;

import com.clawbotforall.model.ModelProviderDefinition;
import com.clawbotforall.model.ModelProviderService;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeExecHandle;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 协调 OpenClaw 容器内的交互式模型认证流程。
 */
@Service
public class ModelAuthService {

  private static final long AUTH_TIMEOUT_MS = 15 * 60 * 1000L;
  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");
  private static final Pattern PROMPT_PATTERN = Pattern.compile("◆\\s+([^\\n\\r]+)");
  private static final Pattern FALLBACK_PROMPT_PATTERN = Pattern.compile(
      "(Paste [^\\n\\r]+|Enter [^\\n\\r]+)$",
      Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
  );

  private final InstanceCommandService commandService;
  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceFileService fileService;
  private final OpenClawRuntime openClawRuntime;
  private final ModelProviderService modelProviderService;
  private final InstanceEventPublisher eventPublisher;
  private final ConcurrentHashMap<String, ModelAuthJob> jobs = new ConcurrentHashMap<>();

  public ModelAuthService(
      InstanceCommandService commandService,
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      InstanceFileService fileService,
      OpenClawRuntime openClawRuntime,
      ModelProviderService modelProviderService,
      InstanceEventPublisher eventPublisher
  ) {
    this.commandService = commandService;
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.fileService = fileService;
    this.openClawRuntime = openClawRuntime;
    this.modelProviderService = modelProviderService;
    this.eventPublisher = eventPublisher;
  }

  /**
   * 启动交互式模型认证命令。
   */

  public void start(InstanceEntity instance) {
    if (isProvisioningRunning(instance.getId())) {
      throw new ApiException(HttpStatus.CONFLICT, "实例仍在启动中，请等待 Gateway 就绪后再进行模型登录。");
    }
    List<InstanceModelEntity> models = commandService.listModels(instance.getId());
    if (models.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请先保存模型配置。");
    }

    InstanceModelEntity primaryModel = models.getFirst();
    ModelProviderDefinition definition = modelProviderService.findByKey(primaryModel.getProviderKey());
    if (definition == null || !definition.supportsInteractiveAuth()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "当前模型不需要交互式登录。");
    }

    ModelAuthJob job = new ModelAuthJob();
    if (jobs.putIfAbsent(instance.getId(), job) != null) {
      throw new ApiException(HttpStatus.CONFLICT, "当前实例已有模型登录任务在执行中。");
    }

    try {
      ensureInstanceRunning(instance, models);
      patchAndPublish(instance.getId(), auth -> {
        auth.setStatus("starting");
        auth.setMessage("正在启动模型登录流程。");
        auth.setOutputSnippet("");
        auth.setAuthUrl("");
        auth.setPromptLabel("");
        auth.setNeedsInput(false);
      });

      String command = buildModelAuthCommand(primaryModel, definition);
      Map<String, String> env = definition.forceRemoteOAuth()
          ? Map.of(
              "SSH_CONNECTION", "127.0.0.1 2222 2222",
              "SSH_CLIENT", "127.0.0.1 2222 2222",
              "SSH_TTY", "/dev/pts/1"
          )
          : Map.of();

      RuntimeExecHandle handle = openClawRuntime.startExec(
          instance,
          command,
          AUTH_TIMEOUT_MS,
          env,
          new RuntimeExecListener() {
            /**
             * 接收模型登录命令输出并推导当前交互状态。
             */
            @Override
            public void onOutput(String output) {
              String combined = job.appendOutput(output);
              ModelAuthState state = inferModelAuthState(combined, definition, null);
              patchAndPublish(instance.getId(), auth -> applyState(auth, state));
            }

            /**
             * 模型登录命令结束后根据退出码写入最终状态。
             */

            @Override
            public void onComplete(int exitCode) {
              jobs.remove(instance.getId());
              if (job.cancelRequested.get()) {
                patchAndPublish(instance.getId(), auth -> {
                  auth.setStatus("cancelled");
                  auth.setMessage("模型登录已取消。");
                  auth.setOutputSnippet(trimTo(job.output(), 4000));
                  auth.setNeedsInput(false);
                });
                return;
              }

              if (exitCode == 0) {
                patchAndPublish(instance.getId(), auth -> {
                  auth.setStatus("success");
                  auth.setMessage("模型登录已完成。");
                  auth.setOutputSnippet(trimTo(job.output(), 4000));
                  auth.setPromptLabel("");
                  auth.setNeedsInput(false);
                });
                return;
              }

              patchAndPublish(instance.getId(), auth -> {
                auth.setStatus("error");
                auth.setMessage("模型登录失败，请查看输出信息。");
                auth.setOutputSnippet(trimTo(job.output(), 4000));
                auth.setNeedsInput(false);
              });
            }

            /**
             * 模型登录命令超时时标记失败并保留输出。
             */

            @Override
            public void onTimeout() {
              jobs.remove(instance.getId());
              patchAndPublish(instance.getId(), auth -> {
                auth.setStatus("error");
                auth.setMessage("模型登录超时，请重试。");
                auth.setOutputSnippet(trimTo(job.output(), 4000));
                auth.setNeedsInput(false);
              });
            }

            /**
             * 模型登录命令异常时记录错误信息。
             */

            @Override
            public void onError(Throwable error) {
              jobs.remove(instance.getId());
              patchAndPublish(instance.getId(), auth -> {
                auth.setStatus("error");
                auth.setMessage("模型登录失败：" + trimTo(error.getMessage() == null ? String.valueOf(error) : error.getMessage(), 180));
                auth.setOutputSnippet(trimTo(job.output(), 4000));
                auth.setNeedsInput(false);
              });
            }
          }
      );
      job.setHandle(handle);
    } catch (RuntimeException error) {
      jobs.remove(instance.getId());
      patchAndPublish(instance.getId(), auth -> {
        auth.setStatus("error");
        auth.setMessage(trimTo(error.getMessage() == null ? String.valueOf(error) : error.getMessage(), 180));
        auth.setNeedsInput(false);
      });
      throw error;
    }
  }

  /**
   * 向待处理的交互式认证命令发送文本。
   */

  public void sendInput(InstanceEntity instance, String text) {
    String input = text == null ? "" : text.trim();
    if (input.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请输入要提交给 CLI 的内容。");
    }

    ModelAuthJob job = jobs.get(instance.getId());
    if (job == null || job.handle == null) {
      throw new ApiException(HttpStatus.CONFLICT, "当前没有等待输入的模型登录任务。");
    }

    job.handle.sendInput(input + "\n");
    patchAndPublish(instance.getId(), auth -> {
      auth.setStatus("running");
      auth.setMessage("输入已发送，等待 CLI 继续处理。");
      auth.setNeedsInput(false);
    });
  }

  /**
   * 取消正在运行的运行时命令。
   */

  public void cancel(InstanceEntity instance) {
    ModelAuthJob job = jobs.get(instance.getId());
    if (job == null) {
      throw new ApiException(HttpStatus.CONFLICT, "当前没有进行中的模型登录任务。");
    }

    job.cancelRequested.set(true);
    if (job.handle != null) {
      job.handle.cancel();
    }
    patchAndPublish(instance.getId(), auth -> {
      auth.setStatus("cancelled");
      auth.setMessage("模型登录已取消。");
      auth.setNeedsInput(false);
    });
    jobs.remove(instance.getId());
  }

  private void ensureInstanceRunning(InstanceEntity instance, List<InstanceModelEntity> models) {
    RuntimeState state = openClawRuntime.inspectInstance(instance);
    if (state.running()) {
      return;
    }

    InstancePaths paths = fileService.writeInstanceFiles(instance, models);
    RuntimeState started = openClawRuntime.startInstance(instance, paths);
    String status = started.status() == null || started.status().isBlank() ? "running" : started.status();
    commandService.updateInstanceStatus(instance.getId(), status);
  }

  private boolean isProvisioningRunning(String instanceId) {
    return aggregateMapper.listProvisioningByInstanceIds(List.of(instanceId))
        .stream()
        .anyMatch(provisioning -> "running".equals(provisioning.getStatus()));
  }

  /**
   * 更新模型认证状态并通过 WebSocket 推送给管理员后台。
   */

  @Transactional
  protected InstanceModelAuthEntity patchAndPublish(
      String instanceId,
      ModelAuthPatch patch
  ) {
    InstanceModelAuthEntity auth = aggregateMapper.listModelAuthByInstanceIds(List.of(instanceId))
        .stream()
        .findFirst()
        .orElseGet(() -> defaultModelAuth(instanceId));
    patch.apply(auth);
    auth.setUpdatedAt(Instant.now().toString());
    mutationMapper.updateModelAuth(auth);
    eventPublisher.publishModelAuthUpdated(instanceId, publicModelAuth(auth));
    return auth;
  }

  private static void applyState(InstanceModelAuthEntity auth, ModelAuthState state) {
    auth.setStatus(state.status());
    auth.setMessage(state.message());
    auth.setOutputSnippet(state.outputSnippet());
    auth.setAuthUrl(state.authUrl());
    auth.setPromptLabel(state.promptLabel());
    auth.setNeedsInput(state.needsInput());
  }

  private static InstanceModelAuthEntity defaultModelAuth(String instanceId) {
    InstanceModelAuthEntity auth = new InstanceModelAuthEntity();
    auth.setInstanceId(instanceId);
    auth.setStatus("idle");
    auth.setMessage("");
    auth.setOutputSnippet("");
    auth.setAuthUrl("");
    auth.setPromptLabel("");
    auth.setNeedsInput(false);
    return auth;
  }

  private static PublicInstanceModelAuth publicModelAuth(InstanceModelAuthEntity auth) {
    return new PublicInstanceModelAuth(
        defaultString(auth.getStatus()),
        auth.getUpdatedAt(),
        defaultString(auth.getMessage()),
        defaultString(auth.getOutputSnippet()),
        defaultString(auth.getAuthUrl()),
        defaultString(auth.getPromptLabel()),
        auth.isNeedsInput()
    );
  }

  static ModelAuthState inferModelAuthState(
      String output,
      ModelProviderDefinition definition,
      ModelAuthState current
  ) {
    String text = defaultString(output);
    String promptLabel = extractPromptLabel(text);
    String authUrl = extractLastUrl(text);
    boolean needsInput = !promptLabel.isBlank()
        || Pattern.compile("paste .*below", Pattern.CASE_INSENSITIVE).matcher(text).find()
        || Pattern.compile("paste .*redirect", Pattern.CASE_INSENSITIVE).matcher(text).find()
        || Pattern.compile("paste .*token", Pattern.CASE_INSENSITIVE).matcher(text).find()
        || Pattern.compile("enter the redirect url", Pattern.CASE_INSENSITIVE).matcher(text).find();

    String message = current == null ? "正在执行登录流程。" : current.message();
    if (definition != null && "device_code".equals(definition.authType()) && !authUrl.isBlank()) {
      needsInput = false;
      promptLabel = "";
      message = "请在浏览器完成授权，CLI 会自动轮询结果。";
    } else if (needsInput) {
      message = "CLI 正在等待输入。";
    }

    return new ModelAuthState(
        needsInput ? "waiting_input" : "running",
        message,
        trimTo(text, 4000),
        authUrl,
        promptLabel,
        needsInput
    );
  }

  private static String buildModelAuthCommand(
      InstanceModelEntity primaryModel,
      ModelProviderDefinition definition
  ) {
    String providerId = firstNonBlank(primaryModel.getAuthProviderId(), definition.authProviderId(), primaryModel.getProviderId());
    String methodId = firstNonBlank(primaryModel.getAuthMethodId(), definition.authMethodId());
    return "openclaw models auth login --provider " + shellQuote(providerId) + " --method " + shellQuote(methodId);
  }

  private static String extractLastUrl(String text) {
    var matcher = URL_PATTERN.matcher(defaultString(text));
    String last = "";
    while (matcher.find()) {
      last = matcher.group();
    }
    return last;
  }

  private static String extractPromptLabel(String text) {
    var matcher = PROMPT_PATTERN.matcher(defaultString(text));
    String last = "";
    while (matcher.find()) {
      last = matcher.group(1).trim();
    }
    if (!last.isBlank()) {
      return last;
    }

    var fallbackMatcher = FALLBACK_PROMPT_PATTERN.matcher(defaultString(text));
    while (fallbackMatcher.find()) {
      last = fallbackMatcher.group(1).trim();
    }
    return last;
  }

  private static String shellQuote(String value) {
    String normalized = defaultString(value);
    return "'" + normalized.replace("'", "'\"'\"'") + "'";
  }

  private static String trimTo(String value, int maxLength) {
    String normalized = defaultString(value);
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(normalized.length() - maxLength);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  record ModelAuthState(
      String status,
      String message,
      String outputSnippet,
      String authUrl,
      String promptLabel,
      boolean needsInput
  ) {}

  @FunctionalInterface
  private interface ModelAuthPatch {
    void apply(InstanceModelAuthEntity auth);
  }

  private static final class ModelAuthJob {
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final StringBuilder output = new StringBuilder();
    private volatile RuntimeExecHandle handle;

    private synchronized String appendOutput(String chunk) {
      output.append(defaultString(chunk));
      if (output.length() > 12_000) {
        output.delete(0, output.length() - 12_000);
      }
      return output.toString();
    }

    private synchronized String output() {
      return output.toString();
    }

    private void setHandle(RuntimeExecHandle handle) {
      this.handle = handle;
      if (cancelRequested.get()) {
        handle.cancel();
      }
    }
  }
}
