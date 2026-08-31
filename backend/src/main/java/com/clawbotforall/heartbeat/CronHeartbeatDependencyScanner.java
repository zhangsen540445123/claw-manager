package com.clawbotforall.heartbeat;

import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.wechat.WechatLogSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 只读扫描仍依赖 Agent Heartbeat 唤醒的 Cron 任务。
 *
 * <p>Cron 与 Agent Heartbeat 是两套机制。本扫描器不会修改任务文件、暂停任务或读取并输出任务正文，
 * 只用于在关闭 Agent Heartbeat 前发现 {@code wakeMode=next-heartbeat} 的兼容性风险。</p>
 */
@Service
public class CronHeartbeatDependencyScanner {
  public static final long MAX_JOBS_FILE_BYTES = 4L * 1024 * 1024;
  public static final int MAX_JOBS = 10_000;

  private static final String NEXT_HEARTBEAT = "next-heartbeat";

  private final InstanceFileService fileService;
  private final ObjectMapper objectMapper;

  public CronHeartbeatDependencyScanner(InstanceFileService fileService, ObjectMapper objectMapper) {
    this.fileService = fileService;
    this.objectMapper = objectMapper;
  }

  public ScanReport scanInstance(String instanceId) {
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("实例 ID 不能为空。");
    }
    InstancePaths paths = fileService.paths(instanceId);
    Path jobsPath = paths.homeDir().resolve(".openclaw").resolve("cron").resolve("jobs.json").normalize();
    LinkedHashSet<String> warnings = new LinkedHashSet<>();
    if (!Files.isRegularFile(jobsPath)) {
      return new ScanReport(hash(instanceId), List.of(), List.of());
    }

    JsonNode root = readJobs(jobsPath, warnings);
    if (root == null) {
      return new ScanReport(hash(instanceId), List.of(), List.copyOf(warnings));
    }
    JsonNode jobs = root.isArray() ? root : root.path("jobs");
    if (!jobs.isArray()) {
      warnings.add("cron_jobs_invalid");
      return new ScanReport(hash(instanceId), List.of(), List.copyOf(warnings));
    }

    List<CronDependency> dependencies = new ArrayList<>();
    int scanned = 0;
    for (JsonNode job : jobs) {
      if (scanned++ >= MAX_JOBS) {
        warnings.add("cron_job_limit_reached");
        break;
      }
      if (!job.isObject() || !job.path("enabled").isBoolean() || !job.path("enabled").booleanValue()) {
        continue;
      }
      String wakeMode = normalizeWakeMode(job.path("wakeMode").asText(""));
      if (!NEXT_HEARTBEAT.equals(wakeMode)) {
        continue;
      }
      String jobId = firstNonBlank(job.path("id").asText(null), job.path("jobId").asText(null));
      if (jobId == null) {
        warnings.add("cron_job_id_missing");
        continue;
      }
      dependencies.add(new CronDependency(hash(jobId), true, NEXT_HEARTBEAT));
    }
    return new ScanReport(hash(instanceId), dependencies, List.copyOf(warnings));
  }

  private JsonNode readJobs(Path jobsPath, LinkedHashSet<String> warnings) {
    try {
      if (Files.size(jobsPath) > MAX_JOBS_FILE_BYTES) {
        warnings.add("cron_jobs_too_large");
        return null;
      }
      return objectMapper.readTree(jobsPath.toFile());
    } catch (IOException error) {
      warnings.add("cron_jobs_invalid");
      return null;
    }
  }

  private static String normalizeWakeMode(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private static String hash(String value) {
    return WechatLogSanitizer.identityHashPreview(value);
  }

  public record CronDependency(String jobIdHash, boolean enabled, String wakeMode) {}

  public record ScanReport(
      String instanceIdHash,
      List<CronDependency> dependencies,
      List<String> warnings
  ) {
    public ScanReport {
      dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }
}
