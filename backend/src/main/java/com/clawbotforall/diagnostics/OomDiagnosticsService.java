package com.clawbotforall.diagnostics;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.runtime.InstanceStats;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeCommandResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 以低开销、只读方式采集 OpenClaw 容器内存诊断数据。
 *
 * <p>采集结果只包含资源数值和进程名白名单字段，不读取或落盘消息、会话、提示词及认证内容。</p>
 */
@Service
public class OomDiagnosticsService {

  private static final Logger log = LoggerFactory.getLogger(OomDiagnosticsService.class);
  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * 1024L * 1024L;
  private static final double SNAPSHOT_MEMORY_PERCENT = 82.0;
  private static final int MAX_COMMAND_OUTPUT_CHARS = 64 * 1024;
  private static final AtomicBoolean SNAPSHOT_IN_PROGRESS = new AtomicBoolean(false);
  private static final ExecutorService SNAPSHOT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "openclaw-heap-snapshot");
    thread.setDaemon(true);
    return thread;
  });

  private static final String PROCESS_SAMPLE_SCRIPT = """
      set +e
      node_pid=""
      fallback_pid=""
      for proc in /proc/[0-9]*; do
        [ -r "$proc/comm" ] || continue
        pid="${proc#/proc/}"
        comm="$(cat "$proc/comm" 2>/dev/null)"
        case "$comm" in
          node|nodejs|openclaw)
            [ -n "$fallback_pid" ] || fallback_pid="$pid"
            if [ "$comm" = "openclaw" ] || tr '\\000' ' ' < "$proc/cmdline" 2>/dev/null | grep -Eiq '(openclaw|gateway)'; then
              node_pid="$pid"
              break
            fi
            ;;
        esac
      done
      [ -n "$node_pid" ] || node_pid="$fallback_pid"
      printf 'node_pid=%s\\n' "${node_pid:-0}"
      if [ -n "$node_pid" ] && [ "$node_pid" != "0" ]; then
        selected_comm="$(cat "/proc/$node_pid/comm" 2>/dev/null)"
        if [ "$selected_comm" = "openclaw" ] || tr '\\000' ' ' < "/proc/$node_pid/cmdline" 2>/dev/null | grep -Eiq '(openclaw|gateway)'; then
          echo 'node_cmd_match=1'
        else
          echo 'node_cmd_match=0'
        fi
        if tr '\\000' '\\n' < "/proc/$node_pid/environ" 2>/dev/null | grep -Eq '^NODE_OPTIONS=.*--heapsnapshot-signal=SIGUSR2'; then
          echo 'snapshot_signal_ready=1'
        else
          echo 'snapshot_signal_ready=0'
        fi
        awk '/VmRSS:/{print "vm_rss_kib="$2} /RssAnon:/{print "rss_anon_kib="$2} /RssFile:/{print "rss_file_kib="$2} /VmSwap:/{print "vm_swap_kib="$2}' "/proc/$node_pid/status" 2>/dev/null
        awk '/Pss:/{pss+=$2} /Private_Dirty:/{private_dirty+=$2} /Anonymous:/{anonymous+=$2} END {print "pss_kib="pss+0; print "private_dirty_kib="private_dirty+0; print "anonymous_kib="anonymous+0}' "/proc/$node_pid/smaps_rollup" 2>/dev/null
      else
        echo 'node_cmd_match=0'
        echo 'snapshot_signal_ready=0'
      fi
      if [ -r /sys/fs/cgroup/memory.current ]; then
        printf 'cgroup_memory_current_bytes='; cat /sys/fs/cgroup/memory.current 2>/dev/null
        printf 'cgroup_memory_swap_current_bytes='; cat /sys/fs/cgroup/memory.swap.current 2>/dev/null
        awk '$1=="oom"{print "cgroup_event_oom="$2} $1=="oom_kill"{print "cgroup_event_oom_kill="$2}' /sys/fs/cgroup/memory.events 2>/dev/null
        awk '$1=="anon"{print "cgroup_anon_bytes="$2} $1=="file"{print "cgroup_file_bytes="$2} $1=="kernel"{print "cgroup_kernel_bytes="$2} $1=="slab"{print "cgroup_slab_bytes="$2} $1=="shmem"{print "cgroup_shmem_bytes="$2} $1=="pgfault"{print "cgroup_pgfault="$2} $1=="pgmajfault"{print "cgroup_pgmajfault="$2}' /sys/fs/cgroup/memory.stat 2>/dev/null
      fi
      (
        for proc in /proc/[0-9]*; do
          [ -r "$proc/status" ] || continue
          pid="${proc#/proc/}"
          ppid="$(awk '/^PPid:/{print $2}' "$proc/status" 2>/dev/null)"
          rss="$(awk '/^VmRSS:/{print $2}' "$proc/status" 2>/dev/null)"
          comm="$(cat "$proc/comm" 2>/dev/null | tr -cd 'A-Za-z0-9._:+-')"
          [ -n "$rss" ] || rss=0
          [ -n "$ppid" ] || ppid=0
          [ -n "$comm" ] || comm=unknown
          printf 'process=%s|%s|%.64s|%s\\n' "$pid" "$ppid" "$comm" "$rss"
        done
      ) | sort -t'|' -k4,4nr | head -n 48
      """;

  private final ClawbotProperties properties;
  private final OpenClawRuntime runtime;
  private final ObjectMapper objectMapper;

  public OomDiagnosticsService(
      ClawbotProperties properties,
      OpenClawRuntime runtime,
      ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.runtime = runtime;
    this.objectMapper = objectMapper;
  }

  /** 采集一个实例；任何诊断失败均在本方法内收敛，不影响实例业务。 */
  public void collect(InstanceEntity instance) {
    if (!properties.oomDiagnostics().enabled() || instance == null || !validInstanceId(instance.getId())) {
      return;
    }
    try {
      Path diagnosticsDir = diagnosticsDirectory(instance.getId());
      Files.createDirectories(diagnosticsDir);
      secureDirectory(diagnosticsDir);
      cleanupExpiredMetrics(diagnosticsDir);

      InstanceStats stats = safeStats(instance);
      RuntimeCommandResult commandResult = runtime.executeReadOnly(
          instance,
          List.of("/bin/sh", "-lc", PROCESS_SAMPLE_SCRIPT),
          12_000,
          MAX_COMMAND_OUTPUT_CHARS
      );
      Map<String, Object> processSample = commandResult.succeeded()
          ? parseProcessSample(commandResult.output())
          : new LinkedHashMap<>();

      Map<String, Object> metric = new LinkedHashMap<>();
      metric.put("timestamp", Instant.now().toString());
      metric.put("instanceHash", sha256Prefix(instance.getId()));
      if (stats != null) {
        metric.put("cpuPercent", stats.cpuPercent());
        metric.put("memoryUsage", stats.memUsage());
        metric.put("memoryPercent", stats.memPercent());
        metric.put("networkIo", stats.netIO());
        metric.put("pids", parseLong(stats.pids(), 0));
      }
      metric.put("sampleStatus", commandResult.succeeded()
          ? "success"
          : commandResult.timedOut() ? "timeout" : "failed");
      metric.putAll(processSample);

      writeLatest(diagnosticsDir, metric);
      appendMetric(diagnosticsDir, metric);
      maybeScheduleHeapSnapshot(instance, diagnosticsDir, stats, processSample);
    } catch (Throwable error) {
      log.warn(
          "OOM 诊断采集失败：instanceId={}, errorType={}",
          instance.getId(),
          error.getClass().getSimpleName()
      );
    }
  }

  static Map<String, Object> parseProcessSample(String output) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<Map<String, Object>> processes = new ArrayList<>();
    List<Map<String, Object>> fileGroups = new ArrayList<>();
    if (output == null || output.isBlank()) {
      return result;
    }
    for (String line : output.split("\\R")) {
      int separator = line.indexOf('=');
      if (separator <= 0) {
        continue;
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 1).trim();
      switch (key) {
        case "node_pid" -> result.put("nodePid", parseLong(value, 0));
        case "node_cmd_match" -> result.put("nodeCommandMatched", "1".equals(value));
        case "snapshot_signal_ready" -> result.put("snapshotSignalReady", "1".equals(value));
        case "vm_rss_kib" -> result.put("vmRssKib", parseLong(value, 0));
        case "rss_anon_kib" -> result.put("rssAnonKib", parseLong(value, 0));
        case "rss_file_kib" -> result.put("rssFileKib", parseLong(value, 0));
        case "vm_swap_kib" -> result.put("vmSwapKib", parseLong(value, 0));
        case "pss_kib" -> result.put("pssKib", parseLong(value, 0));
        case "private_dirty_kib" -> result.put("privateDirtyKib", parseLong(value, 0));
        case "anonymous_kib" -> result.put("anonymousKib", parseLong(value, 0));
        case "cgroup_memory_current_bytes" -> result.put("cgroupMemoryCurrentBytes", parseLong(value, 0));
        case "cgroup_memory_swap_current_bytes" -> result.put("cgroupMemorySwapCurrentBytes", parseLong(value, 0));
        case "cgroup_event_oom" -> result.put("cgroupEventOom", parseLong(value, 0));
        case "cgroup_event_oom_kill" -> result.put("cgroupEventOomKill", parseLong(value, 0));
        case "cgroup_anon_bytes" -> result.put("cgroupAnonBytes", parseLong(value, 0));
        case "cgroup_file_bytes" -> result.put("cgroupFileBytes", parseLong(value, 0));
        case "cgroup_kernel_bytes" -> result.put("cgroupKernelBytes", parseLong(value, 0));
        case "cgroup_slab_bytes" -> result.put("cgroupSlabBytes", parseLong(value, 0));
        case "cgroup_shmem_bytes" -> result.put("cgroupShmemBytes", parseLong(value, 0));
        case "cgroup_pgfault" -> result.put("cgroupPageFaults", parseLong(value, 0));
        case "cgroup_pgmajfault" -> result.put("cgroupMajorPageFaults", parseLong(value, 0));
        case "process" -> parseProcess(value, processes);
        case "file_group" -> parseFileGroup(value, fileGroups);
        default -> {
          // 严格忽略非白名单字段，避免把命令输出中的业务或认证内容写入诊断文件。
        }
      }
    }
    if (!processes.isEmpty()) {
      result.put("processes", List.copyOf(processes));
    }
    if (!fileGroups.isEmpty()) {
      result.put("fileGroups", List.copyOf(fileGroups));
    }
    return result;
  }

  private void maybeScheduleHeapSnapshot(
      InstanceEntity instance,
      Path diagnosticsDir,
      InstanceStats stats,
      Map<String, Object> sample
  ) {
    ClawbotProperties.OomDiagnostics config = properties.oomDiagnostics();
    if (!shouldScheduleHeapSnapshot(config, instance.getId(), stats, sample)) {
      return;
    }
    long nodePid = number(sample.get("nodePid"));
    if (!isValidSnapshotPid(nodePid) || !snapshotCapacityAvailable(diagnosticsDir, instance.getId())) {
      return;
    }
    if (!SNAPSHOT_IN_PROGRESS.compareAndSet(false, true)) {
      return;
    }
    SNAPSHOT_EXECUTOR.execute(() -> {
      try {
        createHeapSnapshot(instance, diagnosticsDir, nodePid);
      } catch (Throwable error) {
        log.warn(
            "Heap Snapshot 采集失败：instanceId={}, errorType={}",
            instance.getId(),
            error.getClass().getSimpleName()
        );
      } finally {
        SNAPSHOT_IN_PROGRESS.set(false);
      }
    });
  }

  static boolean isValidSnapshotPid(long nodePid) {
    return nodePid >= 1;
  }

  static boolean shouldScheduleHeapSnapshot(
      ClawbotProperties.OomDiagnostics config,
      String instanceId,
      InstanceStats stats,
      Map<String, Object> sample
  ) {
    return config != null
        && config.snapshotEnabledFor(instanceId)
        && stats != null
        && parsePercent(stats.memPercent()) >= SNAPSHOT_MEMORY_PERCENT
        && sample != null
        && Boolean.TRUE.equals(sample.get("nodeCommandMatched"))
        && Boolean.TRUE.equals(sample.get("snapshotSignalReady"));
  }
  private void createHeapSnapshot(InstanceEntity instance, Path diagnosticsDir, long nodePid) {
    Path snapshotsDir = diagnosticsDir.resolve("snapshots");
    try {
      Files.createDirectories(snapshotsDir);
      secureDirectory(snapshotsDir);
      Set<String> before = snapshotNames(snapshotsDir);
      RuntimeCommandResult result = runtime.executeReadOnly(
          instance,
          List.of("/bin/sh", "-lc", "kill -USR2 " + nodePid),
          10_000,
          1024
      );
      if (!result.succeeded()) {
        return;
      }
      waitForSnapshot(snapshotsDir, before, Duration.ofMinutes(5));
    } catch (IOException error) {
      log.warn("Heap Snapshot 目录准备失败：instanceId={}, errorType={}", instance.getId(), error.getClass().getSimpleName());
    }
  }

  private boolean snapshotCapacityAvailable(Path diagnosticsDir, String instanceId) {
    try {
      FileStore store = Files.getFileStore(diagnosticsDir);
      if (store.getUsableSpace() < properties.oomDiagnostics().minFreeDiskGib() * GIB) {
        return false;
      }
      Path instancesRoot = instancesRoot();
      List<Path> allSnapshots;
      try (Stream<Path> stream = Files.walk(instancesRoot, 6)) {
        allSnapshots = stream
            .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".heapsnapshot"))
            .toList();
      }
      if (allSnapshots.size() >= properties.oomDiagnostics().heapSnapshotMaxCount()) {
        return false;
      }
      long totalBytes = 0;
      int instanceCount = 0;
      Instant latest = Instant.EPOCH;
      for (Path snapshot : allSnapshots) {
        totalBytes += Files.size(snapshot);
        Instant modified = Files.getLastModifiedTime(snapshot).toInstant();
        if (modified.isAfter(latest)) {
          latest = modified;
        }
        if (snapshot.normalize().startsWith(diagnosticsDir.resolve("snapshots").normalize())) {
          instanceCount++;
        }
      }
      if (instanceCount >= properties.oomDiagnostics().heapSnapshotPerInstanceMaxCount()) {
        return false;
      }
      if (totalBytes >= properties.oomDiagnostics().heapSnapshotMaxTotalGib() * GIB) {
        return false;
      }
      return latest.equals(Instant.EPOCH)
          || Duration.between(latest, Instant.now()).toMillis()
              >= properties.oomDiagnostics().heapSnapshotMinIntervalMs();
    } catch (IOException error) {
      log.warn("Heap Snapshot 容量检查失败：instanceId={}, errorType={}", instanceId, error.getClass().getSimpleName());
      return false;
    }
  }

  private static void waitForSnapshot(Path snapshotsDir, Set<String> before, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    Path candidate = null;
    long previousSize = -1;
    int stableChecks = 0;
    while (System.nanoTime() < deadline) {
      try (Stream<Path> stream = Files.list(snapshotsDir)) {
        Path newest = stream
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith(".heapsnapshot"))
            .filter(path -> !before.contains(path.getFileName().toString()))
            .max(Comparator.comparingLong(OomDiagnosticsService::lastModifiedMillis))
            .orElse(null);
        if (newest != null) {
          long size = Files.size(newest);
          if (newest.equals(candidate) && size > 0 && size == previousSize) {
            stableChecks++;
            if (stableChecks >= 2) {
              return;
            }
          } else {
            candidate = newest;
            stableChecks = 0;
          }
          previousSize = size;
        }
      } catch (IOException ignored) {
        // 快照仍在生成或目录暂不可读时继续等待。
      }
      try {
        Thread.sleep(5_000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void writeLatest(Path diagnosticsDir, Map<String, Object> metric) throws IOException {
    Path target = diagnosticsDir.resolve("memory-latest.json");
    Path temporary = diagnosticsDir.resolve(".memory-latest.json.tmp");
    byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metric);
    Files.write(temporary, content);
    secureFile(temporary);
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException atomicMoveFailed) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
    secureFile(target);
  }

  private void appendMetric(Path diagnosticsDir, Map<String, Object> metric) throws IOException {
    Path metrics = diagnosticsDir.resolve("metrics-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
    byte[] line = (compactJson(metric) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    enforceMetricsLimit(diagnosticsDir, metrics, line.length);
    Files.write(metrics, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    secureFile(metrics);
  }

  private String compactJson(Map<String, Object> metric) throws JsonProcessingException {
    return objectMapper.writeValueAsString(metric);
  }

  private void cleanupExpiredMetrics(Path diagnosticsDir) throws IOException {
    Instant cutoff = Instant.now().minus(Duration.ofDays(properties.oomDiagnostics().retentionDays()));
    try (Stream<Path> stream = Files.list(diagnosticsDir)) {
      for (Path path : stream.filter(Files::isRegularFile).toList()) {
        String name = path.getFileName().toString();
        if ((name.startsWith("metrics-") || name.startsWith("diagnostics-"))
            && name.endsWith(".jsonl")
            && Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private void enforceMetricsLimit(Path diagnosticsDir, Path current, int nextLineBytes) throws IOException {
    long limit = properties.oomDiagnostics().metricsLimitMib() * MIB;
    List<Path> files;
    try (Stream<Path> stream = Files.list(diagnosticsDir)) {
      files = stream
          .filter(Files::isRegularFile)
          .filter(path -> {
            String name = path.getFileName().toString();
            return name.endsWith(".jsonl") && (name.startsWith("metrics-") || name.startsWith("diagnostics-"));
          })
          .sorted(Comparator.comparingLong(OomDiagnosticsService::lastModifiedMillis))
          .toList();
    }
    long total = 0;
    for (Path file : files) {
      total += Files.size(file);
    }
    for (Path file : files) {
      if (total + nextLineBytes <= limit) {
        break;
      }
      if (file.equals(current)) {
        continue;
      }
      long size = Files.size(file);
      Files.deleteIfExists(file);
      total -= size;
    }
    if (Files.exists(current) && Files.size(current) + nextLineBytes > limit) {
      Files.write(current, new byte[0], java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }
  }

  private InstanceStats safeStats(InstanceEntity instance) {
    try {
      return runtime.getStats(instance);
    } catch (Throwable error) {
      return null;
    }
  }

  private Path diagnosticsDirectory(String instanceId) {
    Path root = instancesRoot();
    Path path = root.resolve(instanceId).resolve("home/diagnostics/oom").normalize();
    if (!path.startsWith(root)) {
      throw new IllegalArgumentException("实例诊断目录越界。");
    }
    return path;
  }

  private Path instancesRoot() {
    return Path.of(properties.paths().dataDir()).toAbsolutePath().normalize().resolve("instances").normalize();
  }

  private static void parseProcess(String value, List<Map<String, Object>> processes) {
    if (processes.size() >= 64) {
      return;
    }
    String[] parts = value.split("\\|", -1);
    if (parts.length != 4) {
      return;
    }
    Map<String, Object> process = new LinkedHashMap<>();
    process.put("pid", parseLong(parts[0], 0));
    process.put("ppid", parseLong(parts[1], 0));
    process.put("comm", sanitizeProcessName(parts[2]));
    process.put("rssKib", parseLong(parts[3], 0));
    processes.add(process);
  }

  private static void parseFileGroup(String value, List<Map<String, Object>> fileGroups) {
    if (fileGroups.size() >= 32) {
      return;
    }
    String[] parts = value.split("\\|", -1);
    if (parts.length != 4) {
      return;
    }
    Map<String, Object> group = new LinkedHashMap<>();
    group.put("group", sanitizeProcessName(parts[0]));
    group.put("count", parseLong(parts[1], 0));
    group.put("bytes", parseLong(parts[2], 0));
    group.put("latestMtimeEpoch", parseLong(parts[3], 0));
    fileGroups.add(group);
  }

  private static String sanitizeProcessName(String value) {
    if (value == null) {
      return "unknown";
    }
    String sanitized = value.replaceAll("[^A-Za-z0-9._:+-]", "");
    if (sanitized.isBlank()) {
      return "unknown";
    }
    return sanitized.substring(0, Math.min(64, sanitized.length()));
  }

  private static boolean validInstanceId(String instanceId) {
    return instanceId != null && instanceId.matches("[A-Za-z0-9_-]{1,128}");
  }

  private static long number(Object value) {
    return value instanceof Number number ? number.longValue() : 0;
  }

  private static long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(value == null ? "" : value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static double parsePercent(String value) {
    try {
      return Double.parseDouble(value == null ? "" : value.replace("%", "").trim());
    } catch (NumberFormatException ignored) {
      return 0.0;
    }
  }

  private static Set<String> snapshotNames(Path snapshotsDir) throws IOException {
    if (!Files.isDirectory(snapshotsDir)) {
      return Set.of();
    }
    try (Stream<Path> stream = Files.list(snapshotsDir)) {
      return stream.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
    }
  }

  private static long lastModifiedMillis(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException ignored) {
      return 0;
    }
  }

  private static String sha256Prefix(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (Exception error) {
      return "unavailable";
    }
  }

  private static void secureDirectory(Path directory) {
    try {
      Files.setPosixFilePermissions(directory, Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE
      ));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Windows 或不支持 POSIX 权限的文件系统忽略。
    }
  }

  private static void secureFile(Path file) {
    try {
      Files.setPosixFilePermissions(file, Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE
      ));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Windows 或不支持 POSIX 权限的文件系统忽略。
    }
  }
}
