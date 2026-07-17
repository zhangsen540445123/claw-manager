package com.clawbotforall.trace;

import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IntegrationTraceService {
  private static final Duration DISPATCH_TIMEOUT = Duration.ofMinutes(10);
  private static final Set<String> STATUSES = Set.of("started", "completed", "failed");
  private static final Set<String> CHANNELS = Set.of("wechat", "api", "internal");
  private static final Set<String> COMPONENTS = Set.of("wechat-plugin", "api-channel", "miniapp-bridge", "claw-manager", "time-manager");
  private static final Set<String> STAGES = Set.of(
      "wechat.inbound.received", "wechat.route.resolved", "openclaw.dispatch.started", "openclaw.dispatch.completed",
      "openclaw.dispatch.failed", "wechat.text.send.started", "wechat.text.send.completed", "wechat.text.send.failed",
      "wechat.media.send.started", "wechat.media.send.completed", "wechat.media.send.failed",
      "bridge.tool.started", "bridge.tool.completed", "bridge.tool.failed", "bridge.image_generate.started",
      "bridge.image_generate.completed", "bridge.image_generate.failed", "bridge.publish_image.started",
      "bridge.publish_image.completed", "bridge.publish_image.failed", "image.config.validated",
      "image.provider.request.started", "image.provider.request.completed", "image.provider.request.failed",
      "image.response.decoded", "image.file.written", "image.generation.completed", "image.generation.failed",
      "artifact.identity.resolved", "artifact.image.upload.started", "artifact.image.upload.completed",
      "artifact.image.upload.failed", "artifact.html.create.started", "artifact.html.create.completed",
      "artifact.html.create.failed", "api.request.received", "api.dispatch.started", "api.dispatch.completed",
      "api.dispatch.failed", "api.artifact.emitted", "api.stream.completed");
  private static final Set<String> DETAIL_KEYS = Set.of("modelId", "mime", "width", "height", "fileSize", "imageId", "generatedImageId", "contentKey", "imageRequested");
  private final IntegrationTraceMapper mapper;
  private final ObjectMapper json;

  public IntegrationTraceService(IntegrationTraceMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

  public void record(IntegrationTraceEventRequest request, String headerTraceId) {
    if (request == null) throw new ApiException(HttpStatus.BAD_REQUEST, "trace 事件不能为空。");
    String traceId = value(request == null ? null : request.traceId());
    if (traceId.isBlank()) traceId = value(headerTraceId);
    if (!traceId.matches("cmtrace_[A-Za-z0-9_-]{6,90}")) throw new ApiException(HttpStatus.BAD_REQUEST, "cmTraceId 格式不正确。");
    String status = value(request.status());
    String channel = value(request.channel());
    String component = value(request.component());
    String stage = value(request.stage());
    if (!STATUSES.contains(status) || !CHANNELS.contains(channel)) throw new ApiException(HttpStatus.BAD_REQUEST, "trace 状态或渠道不正确。");
    if (!COMPONENTS.contains(component) || !STAGES.contains(stage)) throw new ApiException(HttpStatus.BAD_REQUEST, "trace 组件或阶段不正确。");
    Map<String, Object> details = new LinkedHashMap<>();
    if (request.details() != null) request.details().forEach((key, val) -> { if (DETAIL_KEYS.contains(key) && val != null) details.put(key, val); });
    mapper.insert(new IntegrationTraceEvent("traceevt_" + UUID.randomUUID().toString().replace("-", ""), traceId,
        value(request.parentRequestId()), component, stage, status, channel,
        clip(request.instanceId(), 64), clip(request.senderHash(), 128), clip(request.sessionKeyHash(), 128),
        clip(request.toolName(), 100), clip(request.requestId(), 128), request.httpStatus(), request.businessCode(),
        request.elapsedMs(), clip(request.errorCode(), 80), sanitize(request.errorMessage()), write(details), Instant.now().toString()));
  }

  public Map<String, Object> list(String instanceId, String channel, String status, String component, String stage,
      String diagnosisCode, String from, String to, int page, int size) {
    int safeSize = Math.max(1, Math.min(size, 100));
    List<IntegrationTraceEvent> rows = mapper.listEvents(value(instanceId), value(channel), "", "", value(from), value(to), safeSize * 20, 0);
    LinkedHashMap<String, List<IntegrationTraceEvent>> grouped = new LinkedHashMap<>();
    for (IntegrationTraceEvent row : rows) grouped.computeIfAbsent(row.traceId(), ignored -> new ArrayList<>()).add(row);
    grouped.values().forEach(events -> events.sort(Comparator.comparing(IntegrationTraceEvent::createdAt)));
    List<List<IntegrationTraceEvent>> filtered = grouped.values().stream()
        .filter(events -> value(status).isBlank() || traceStatus(events).equals(value(status)))
        .filter(events -> value(component).isBlank() || events.stream().anyMatch(event -> value(component).equals(event.component())))
        .filter(events -> value(stage).isBlank() || events.stream().anyMatch(event -> value(stage).equals(event.stage())))
        .filter(events -> value(diagnosisCode).isBlank() || value(diagnosisCode).equals(diagnosis(events).get("code")))
        .sorted(Comparator.comparing((List<IntegrationTraceEvent> events) -> events.getFirst().createdAt()).reversed())
        .toList();
    List<Map<String, Object>> summaries = filtered.stream().skip((long) Math.max(0, page - 1) * safeSize).limit(safeSize).map(this::summary).toList();
    return Map.of("items", summaries, "page", Math.max(1, page), "size", safeSize, "hasMore", filtered.size() > page * safeSize);
  }

  public Map<String, Object> list(String instanceId, String channel, String status, String stage, String from, String to, int page, int size) {
    return list(instanceId, channel, status, "", stage, "", from, to, page, size);
  }

  public Map<String, Object> detail(String traceId) {
    List<IntegrationTraceEvent> events = mapper.findByTraceId(traceId);
    if (events.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "链路记录不存在。");
    Map<String, Object> summary = summary(events);
    List<Map<String, Object>> timeline = events.stream().map(this::publicEvent).toList();
    LinkedHashSet<String> requestIds = new LinkedHashSet<>();
    events.forEach(event -> { if (!event.requestId().isBlank()) requestIds.add(event.requestId()); if (!event.parentRequestId().isBlank()) requestIds.add(event.parentRequestId()); });
    return Map.of("summary", summary, "diagnosis", diagnosis(events), "timeline", timeline,
        "relatedRequestIds", requestIds, "artifact", artifact(events));
  }

  private Map<String, Object> artifact(List<IntegrationTraceEvent> events) {
    LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
    for (IntegrationTraceEvent event : events) {
      Object details = read(event.detailJson());
      if (!(details instanceof Map<?, ?> map)) continue;
      for (String key : List.of("imageId", "generatedImageId", "contentKey", "mime", "width", "height", "fileSize")) {
        Object value = map.get(key);
        if (value != null && !String.valueOf(value).isBlank()) artifact.put(key, value);
      }
    }
    if (artifact.containsKey("imageId")) artifact.put("type", "image_report");
    else if (artifact.containsKey("contentKey")) artifact.put("type", "html_report");
    return artifact;
  }

  private Map<String, Object> summary(List<IntegrationTraceEvent> events) {
    IntegrationTraceEvent first = events.getFirst();
    IntegrationTraceEvent last = events.getLast();
    Map<String, String> diagnosis = diagnosis(events);
    long elapsed = Math.max(0, Instant.parse(last.createdAt()).toEpochMilli() - Instant.parse(first.createdAt()).toEpochMilli());
    return Map.ofEntries(Map.entry("traceId", first.traceId()), Map.entry("channel", first.channel()),
        Map.entry("instanceId", first.instanceId()), Map.entry("senderHashPreview", preview(first.senderHash())),
        Map.entry("startedAt", first.createdAt()), Map.entry("finishedAt", last.createdAt()), Map.entry("elapsedMs", elapsed),
        Map.entry("status", traceStatus(events)), Map.entry("lastStage", last.stage()),
        Map.entry("diagnosisCode", diagnosis.get("code")), Map.entry("diagnosisMessage", diagnosis.get("message")));
  }

  private Map<String, Object> publicEvent(IntegrationTraceEvent e) {
    return Map.ofEntries(Map.entry("component", e.component()), Map.entry("stage", e.stage()), Map.entry("status", e.status()),
        Map.entry("requestId", e.requestId()), Map.entry("toolName", e.toolName()), Map.entry("httpStatus", e.httpStatus() == null ? 0 : e.httpStatus()),
        Map.entry("businessCode", e.businessCode() == null ? 0 : e.businessCode()), Map.entry("elapsedMs", e.elapsedMs() == null ? 0 : e.elapsedMs()),
        Map.entry("errorCode", e.errorCode()), Map.entry("errorMessage", e.errorMessage()), Map.entry("details", read(e.detailJson())), Map.entry("createdAt", e.createdAt()));
  }

  private Map<String, String> diagnosis(List<IntegrationTraceEvent> events) {
    if (failed(events, "wechat.media.send.failed")) return d("WECHAT_MEDIA_FAILED", "Artifact 已完成，但微信媒体发送失败");
    if (failed(events, "artifact.html.create.failed")) return d("ARTIFACT_HTML_FAILED", "图片已上传，但 HTML 创建失败");
    if (failed(events, "artifact.image.upload.failed")) return d("ARTIFACT_UPLOAD_FAILED", "图片已生成，但上传到 Time Manager 失败");
    if (failed(events, "image.file.written")) return d("IMAGE_FILE_WRITE_FAILED", "图片生成响应成功，但文件写入失败");
    if (failed(events, "image.response.decoded")) return d("IMAGE_DECODE_FAILED", "图片响应解码或格式校验失败");
    if (failed(events, "image.provider.request.failed")) return d("IMAGE_PROVIDER_FAILED", "图片服务请求失败");
    if (failed(events, "image.config.validated")) return d("IMAGE_TOOL_FAILED", "图片生成配置未启用或配置不完整");
    if (failed(events, "image.generation.failed")) return d("IMAGE_TOOL_FAILED", "图片生成执行失败");
    if (failed(events, "bridge.image_generate.failed")) return d("IMAGE_TOOL_FAILED", "image_generate 工具执行失败");
    if (failed(events, "bridge.publish_image.failed")) return d("ARTIFACT_TOOL_FAILED", "miniapp_artifact 工具执行失败");
    if (failedTool(events, "miniapp_artifact")) return d("ARTIFACT_TOOL_FAILED", "miniapp_artifact 工具执行失败");
    if (failedTool(events, "image_generate")) return d("IMAGE_TOOL_FAILED", "image_generate 工具执行失败");
    if (events.stream().anyMatch(e -> "failed".equals(e.status()))) return d("FAILED", "链路执行失败，请查看最后一个失败阶段");
    if (imageRequested(events) && apiImageCompleted(events)) return d("COMPLETE", "生图与发布链路完成");
    if (imageRequested(events) && wechatImageCompleted(events)) return d("COMPLETE", "生图与发布链路完成");
    if (!imageRequested(events) && has(events, "api.stream.completed")) return d("COMPLETE", "消息处理链路完成");
    if (!imageRequested(events) && has(events, "openclaw.dispatch.completed")
        && (has(events, "wechat.text.send.completed") || has(events, "wechat.media.send.completed"))) {
      return d("COMPLETE", "消息处理链路完成");
    }
    if (dispatchStarted(events) && !dispatchTerminal(events) && timedOut(events, dispatchStart(events))) {
      return d("DISPATCH_TIMEOUT", "OpenClaw 调度超过 10 分钟仍未结束");
    }
    if (requestReceived(events) && !dispatchStarted(events) && !dispatchTerminal(events)
        && timedOut(events, events.getFirst().createdAt())) {
      return d("NO_OPENCLAW_DISPATCH", "请求已收到，但没有进入 OpenClaw");
    }
    if (imageRequested(events) && dispatchCompleted(events) && !has(events, "bridge.image_generate.started")) {
      return d("NO_IMAGE_TOOL_CALL", "OpenClaw 已回复，但没有调用 image_generate");
    }
    if (has(events, "bridge.image_generate.started") && dispatchCompleted(events) && !has(events, "image.provider.request.started")) {
      return d("IMAGE_PROVIDER_NOT_CALLED", "image_generate 已调用，但图片 Provider 未收到请求");
    }
    if (has(events, "image.file.written") && dispatchCompleted(events) && !startedTool(events, "miniapp_artifact")) {
      return d("ARTIFACT_NOT_CALLED", "图片已生成，但没有调用 publish_image");
    }
    if (imageRequested(events) && has(events, "api.stream.completed") && !apiImageCompleted(events)) {
      return d("ARTIFACT_TOOL_FAILED", "API 流已结束，但 Artifact 发布未完成");
    }
    return d("IN_PROGRESS", "链路尚未完成");
  }

  private boolean has(List<IntegrationTraceEvent> events, String stage) { return events.stream().anyMatch(e -> stage.equals(e.stage())); }
  private boolean failed(List<IntegrationTraceEvent> events, String stage) { return events.stream().anyMatch(e -> stage.equals(e.stage()) && "failed".equals(e.status())); }
  private boolean startedTool(List<IntegrationTraceEvent> events, String toolName) {
    return events.stream().anyMatch(e -> "bridge.tool.started".equals(e.stage()) && toolName.equals(e.toolName()));
  }
  private boolean failedTool(List<IntegrationTraceEvent> events, String toolName) {
    return events.stream().anyMatch(e -> "bridge.tool.failed".equals(e.stage()) && toolName.equals(e.toolName()));
  }
  private boolean dispatchStarted(List<IntegrationTraceEvent> events) {
    return has(events, "openclaw.dispatch.started") || has(events, "api.dispatch.started");
  }
  private boolean requestReceived(List<IntegrationTraceEvent> events) {
    return has(events, "wechat.inbound.received") || has(events, "api.request.received");
  }
  private boolean dispatchCompleted(List<IntegrationTraceEvent> events) {
    return has(events, "openclaw.dispatch.completed") || has(events, "api.dispatch.completed");
  }
  private boolean dispatchTerminal(List<IntegrationTraceEvent> events) {
    return dispatchCompleted(events) || has(events, "openclaw.dispatch.failed") || has(events, "api.dispatch.failed");
  }
  private String dispatchStart(List<IntegrationTraceEvent> events) {
    return events.stream().filter(e -> "openclaw.dispatch.started".equals(e.stage()) || "api.dispatch.started".equals(e.stage()))
        .map(IntegrationTraceEvent::createdAt).min(String::compareTo).orElse(events.getFirst().createdAt());
  }
  private boolean apiImageCompleted(List<IntegrationTraceEvent> events) {
    return has(events, "artifact.html.create.completed") && has(events, "api.artifact.emitted") && has(events, "api.stream.completed");
  }
  private boolean wechatImageCompleted(List<IntegrationTraceEvent> events) {
    return has(events, "artifact.html.create.completed") && has(events, "wechat.media.send.completed") && has(events, "openclaw.dispatch.completed");
  }
  private boolean timedOut(List<IntegrationTraceEvent> events, String startedAt) {
    try { return Instant.parse(startedAt).plus(DISPATCH_TIMEOUT).isBefore(Instant.now()); }
    catch (RuntimeException ignored) { return false; }
  }
  private boolean imageRequested(List<IntegrationTraceEvent> events) {
    for (IntegrationTraceEvent event : events) {
      Object details = read(event.detailJson());
      if (details instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("imageRequested"))) return true;
    }
    return false;
  }
  private String traceStatus(List<IntegrationTraceEvent> events) {
    String code = diagnosis(events).get("code");
    if ("COMPLETE".equals(code)) return "completed";
    return "IN_PROGRESS".equals(code) ? "in_progress" : "failed";
  }
  private Map<String, String> d(String code, String message) { return Map.of("code", code, "message", message); }
  private String sanitize(String s) { return clip(value(s).replaceAll("(?i)Bearer\\s+\\S+", "Bearer ***")
      .replaceAll("cm_user_[A-Za-z0-9_-]+", "cm_user_***").replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***"), 500); }
  private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { return "{}"; } }
  private Object read(String value) { try { return json.readValue(value == null ? "{}" : value, Object.class); } catch (Exception e) { return Map.of(); } }
  private String preview(String s) { String v=value(s); return v.length() <= 12 ? v : v.substring(0, 6) + "..." + v.substring(v.length()-4); }
  private String clip(String s, int max) { String v=value(s); return v.length() <= max ? v : v.substring(0, max); }
  private String value(String s) { return s == null ? "" : s.trim(); }

  @Scheduled(fixedDelay = 3_600_000)
  public void cleanup() { mapper.deleteBefore(Instant.now().minus(7, ChronoUnit.DAYS).toString()); }
}
