package com.clawbotforall.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SkillFileSynchronizer {

  private static final String SKILL_FILE = "SKILL.md";

  public Path copySkill(Path sourceSkillDir, Path workspaceSkillsDir, String skillName) {
    validateSkillName(skillName);
    if (sourceSkillDir == null || !Files.isDirectory(sourceSkillDir)) {
      throw new IllegalArgumentException("Skill 源目录不存在。");
    }

    try {
      Files.createDirectories(workspaceSkillsDir);
      Path targetDir = workspaceSkillsDir.resolve(skillName).normalize();
      if (!targetDir.startsWith(workspaceSkillsDir.normalize())) {
        throw new IllegalArgumentException("Skill 名称不能包含路径穿越。");
      }
      Path tempDir = Files.createTempDirectory(workspaceSkillsDir, "." + skillName + "-");
      copyDirectory(sourceSkillDir, tempDir);
      rewriteSkillName(tempDir.resolve(SKILL_FILE), skillName);
      deleteRecursively(targetDir);
      Files.move(tempDir, targetDir, StandardCopyOption.REPLACE_EXISTING);
      return targetDir;
    } catch (IOException error) {
      throw new UncheckedIOException("同步 Skill 文件失败。", error);
    }
  }

  private void copyDirectory(Path source, Path target) throws IOException {
    try (var stream = Files.walk(source)) {
      for (Path current : stream.toList()) {
        Path relative = source.relativize(current);
        Path destination = target.resolve(relative.toString());
        if (Files.isDirectory(current)) {
          Files.createDirectories(destination);
        } else {
          Files.createDirectories(destination.getParent());
          Files.copy(current, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  private void rewriteSkillName(Path skillFile, String skillName) throws IOException {
    if (!Files.exists(skillFile)) {
      return;
    }
    List<String> lines = Files.readAllLines(skillFile, StandardCharsets.UTF_8);
    if (lines.isEmpty() || !"---".equals(lines.getFirst().trim())) {
      return;
    }

    List<String> rewritten = new ArrayList<>(lines);
    int frontmatterEnd = -1;
    int nameIndex = -1;
    for (int index = 1; index < lines.size(); index++) {
      String line = lines.get(index);
      if ("---".equals(line.trim())) {
        frontmatterEnd = index;
        break;
      }
      if (line.trim().startsWith("name:")) {
        nameIndex = index;
      }
    }

    if (frontmatterEnd < 0) {
      return;
    }
    if (nameIndex >= 0) {
      rewritten.set(nameIndex, "name: " + skillName);
    } else {
      rewritten.add(1, "name: " + skillName);
    }
    Files.writeString(skillFile, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
  }

  private void deleteRecursively(Path path) throws IOException {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (var stream = Files.walk(path)) {
      List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
      for (Path item : paths) {
        Files.deleteIfExists(item);
      }
    }
  }

  private static void validateSkillName(String skillName) {
    if (skillName == null || skillName.isBlank() || skillName.contains("/") || skillName.contains("\\")) {
      throw new IllegalArgumentException("Skill 名称无效。");
    }
  }
}
