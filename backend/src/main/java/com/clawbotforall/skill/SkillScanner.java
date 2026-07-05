package com.clawbotforall.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SkillScanner {

  private static final String SKILL_FILE = "SKILL.md";

  public List<SkillScanResult> scan(String repositoryId, Path repositoryRoot, String commitSha) {
    if (repositoryRoot == null || !Files.isDirectory(repositoryRoot)) {
      return List.of();
    }

    try (var stream = Files.walk(repositoryRoot)) {
      return stream
          .filter(path -> Files.isRegularFile(path) && SKILL_FILE.equals(path.getFileName().toString()))
          .map(Path::getParent)
          .sorted(Comparator.comparing(path -> relativePath(repositoryRoot, path)))
          .map(path -> scanSkill(repositoryId, repositoryRoot, path, commitSha))
          .toList();
    } catch (IOException error) {
      throw new UncheckedIOException("扫描 Skill 仓库失败。", error);
    }
  }

  private SkillScanResult scanSkill(String repositoryId, Path repositoryRoot, Path skillDir, String commitSha) {
    String originalName = skillDir.getFileName().toString();
    String relativePath = relativePath(repositoryRoot, skillDir);
    Path skillFile = skillDir.resolve(SKILL_FILE);
    Map<String, String> frontmatter = readFrontmatter(skillFile);
    String description = frontmatter.getOrDefault("description", "").trim();
    List<String> warnings = new ArrayList<>();
    String frontmatterName = frontmatter.getOrDefault("name", "").trim();
    if (!frontmatterName.isBlank() && !frontmatterName.equals(originalName)) {
      warnings.add("frontmatter name \"" + frontmatterName + "\" differs from directory name \"" + originalName + "\"");
    }
    if (description.isBlank()) {
      warnings.add("description is required");
    }

    return new SkillScanResult(
        repositoryId,
        originalName,
        originalName,
        relativePath,
        description,
        contentHash(skillDir),
        description.isBlank() ? false : true,
        List.copyOf(warnings),
        commitSha
    );
  }

  private Map<String, String> readFrontmatter(Path skillFile) {
    try {
      String content = Files.readString(skillFile, StandardCharsets.UTF_8);
      List<String> lines = content.lines().toList();
      if (lines.isEmpty() || !"---".equals(lines.getFirst().trim())) {
        return Map.of();
      }

      Map<String, String> values = new LinkedHashMap<>();
      for (int index = 1; index < lines.size(); index++) {
        String line = lines.get(index);
        if ("---".equals(line.trim())) {
          break;
        }
        int separator = line.indexOf(':');
        if (separator <= 0) {
          continue;
        }
        String key = line.substring(0, separator).trim();
        String value = line.substring(separator + 1).trim();
        values.put(key, stripQuotes(value));
      }
      return values;
    } catch (IOException error) {
      throw new UncheckedIOException("读取 Skill 元数据失败。", error);
    }
  }

  private String contentHash(Path skillDir) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.walk(skillDir)) {
        List<Path> files = stream
            .filter(Files::isRegularFile)
            .sorted(Comparator.comparing(path -> relativePath(skillDir, path)))
            .toList();
        for (Path file : files) {
          digest.update(relativePath(skillDir, file).getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
          digest.update(Files.readAllBytes(file));
          digest.update((byte) 0);
        }
      }
      return toHex(digest.digest());
    } catch (IOException error) {
      throw new UncheckedIOException("计算 Skill 内容 hash 失败。", error);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256。", error);
    }
  }

  private static String relativePath(Path root, Path path) {
    return root.relativize(path).toString().replace('\\', '/');
  }

  private static String stripQuotes(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte item : bytes) {
      builder.append(String.format("%02x", item));
    }
    return builder.toString();
  }
}
