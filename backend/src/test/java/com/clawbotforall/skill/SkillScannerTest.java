package com.clawbotforall.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillScannerTest {

  @TempDir
  Path tempDir;

  @Test
  void scansRepositorySkillsUsingDirectoryNameAsEditableDefault() throws Exception {
    Path repo = tempDir.resolve("repo");
    Path nestedSkill = repo.resolve("packs").resolve("alpha-skill");
    Files.createDirectories(nestedSkill);
    Files.writeString(
        nestedSkill.resolve("SKILL.md"),
        """
        ---
        name: ignored-frontmatter-name
        description: Alpha skill from GitHub.
        ---

        Use this skill for alpha work.
        """,
        StandardCharsets.UTF_8
    );

    Path invalidSkill = repo.resolve("broken-skill");
    Files.createDirectories(invalidSkill);
    Files.writeString(
        invalidSkill.resolve("SKILL.md"),
        """
        ---
        name: broken-skill
        ---

        Missing description.
        """,
        StandardCharsets.UTF_8
    );

    SkillScanner scanner = new SkillScanner();

    List<SkillScanResult> results = scanner.scan("repo_1", repo, "abc123");

    assertThat(results).hasSize(2);
    SkillScanResult alpha = results.stream()
        .filter(result -> result.relativePath().equals("packs/alpha-skill"))
        .findFirst()
        .orElseThrow();
    assertThat(alpha.skillName()).isEqualTo("alpha-skill");
    assertThat(alpha.originalName()).isEqualTo("alpha-skill");
    assertThat(alpha.description()).isEqualTo("Alpha skill from GitHub.");
    assertThat(alpha.contentHash()).isNotBlank();
    assertThat(alpha.syncable()).isTrue();
    assertThat(alpha.warnings()).contains("frontmatter name \"ignored-frontmatter-name\" differs from directory name \"alpha-skill\"");

    SkillScanResult broken = results.stream()
        .filter(result -> result.relativePath().equals("broken-skill"))
        .findFirst()
        .orElseThrow();
    assertThat(broken.skillName()).isEqualTo("broken-skill");
    assertThat(broken.syncable()).isFalse();
    assertThat(broken.warnings()).contains("description is required");
  }
}
