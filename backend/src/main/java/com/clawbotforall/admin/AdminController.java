package com.clawbotforall.admin;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RunnerImageStatus;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.ws.AppEvent;
import com.clawbotforall.ws.AppEventPublisher;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供管理员后台的运行镜像和服务日志 API。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private static final String ADMIN_RUNNER_IMAGE_TOPIC = "/topic/admin/runner-image";
  private static final int MAX_LOG_TAIL_BYTES = 2 * 1024 * 1024;

  private final OpenClawRuntime openClawRuntime;
  private final ClawbotProperties properties;
  private final AppEventPublisher appEventPublisher;

  public AdminController(
      OpenClawRuntime openClawRuntime,
      ClawbotProperties properties,
      AppEventPublisher appEventPublisher
  ) {
    this.openClawRuntime = openClawRuntime;
    this.properties = properties;
    this.appEventPublisher = appEventPublisher;
  }

  /**
   * 返回当前 OpenClaw 运行镜像状态。
   */
  @GetMapping("/runner-image")
  public Map<String, Object> runnerImage(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("image", openClawRuntime.getRunnerImageStatus());
  }

  /**
   * 从 Docker 刷新本地 OpenClaw 运行镜像状态。
   */
  @PostMapping("/runner-image/refresh")
  public Map<String, Object> refreshRunnerImage(Authentication authentication) {
    requireAdmin(authentication);
    RunnerImageStatus image = openClawRuntime.refreshRunnerImage();
    appEventPublisher.sendToTopic(
        ADMIN_RUNNER_IMAGE_TOPIC,
        AppEvent.of("runnerImage.updated", traceId(), Map.of("image", image))
    );
    return Map.of("image", image);
  }

  /**
   * 返回后端服务日志中受限数量的末尾内容。
   */
  @GetMapping("/server-logs")
  public Map<String, Object> serverLogs(
      @RequestParam(defaultValue = "400") int tail,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    int normalizedTail = Math.max(50, Math.min(2000, tail));
    Path logPath = Path.of(properties.paths().dataDir(), "logs", "server.log");
    List<String> lines = readTail(logPath, normalizedTail);
    return Map.of("logs", Map.of(
        "path", logPath.toString(),
        "tail", normalizedTail,
        "lines", lines,
        "text", String.join("\n", lines)
    ));
  }

  private static List<String> readTail(Path path, int tail) {
    if (!Files.exists(path)) {
      return List.of();
    }
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
      long length = file.length();
      if (length == 0) {
        return List.of();
      }
      long start = tailStart(file, length, tail);
      file.seek(start);
      byte[] bytes = new byte[(int) Math.min(Integer.MAX_VALUE, length - start)];
      file.readFully(bytes);
      List<String> lines = new java.util.ArrayList<>(new String(bytes, StandardCharsets.UTF_8).lines().toList());
      if (lines.size() <= tail) {
        return lines;
      }
      return lines.subList(lines.size() - tail, lines.size());
    } catch (IOException error) {
      return List.of("读取服务日志失败：" + error.getMessage());
    }
  }

  private static long tailStart(RandomAccessFile file, long length, int tail) throws IOException {
    long minPosition = Math.max(0, length - MAX_LOG_TAIL_BYTES);
    int lineBreaks = 0;
    for (long position = length - 1; position >= minPosition; position--) {
      file.seek(position);
      int value = file.read();
      if (value != '\n') {
        continue;
      }
      if (position == length - 1) {
        continue;
      }
      lineBreaks++;
      if (lineBreaks == tail) {
        return position + 1;
      }
    }
    return minPosition;
  }

  private static String traceId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  private static AuthenticatedAdmin requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin admin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
    return admin;
  }
}
