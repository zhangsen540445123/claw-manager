package com.clawbotforall.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillFileSynchronizerTest {

  @TempDir
  Path tempDir;

  @Test
  void copiesSkillToWorkspaceNameAndRewritesFrontmatterName() throws Exception {
    Path source = tempDir.resolve("source").resolve("original-skill");
    Files.createDirectories(source.resolve("references"));
    Files.writeString(
        source.resolve("SKILL.md"),
        """
        ---
        name: original-skill
        description: Original description.
        ---

        Body.
        """,
        StandardCharsets.UTF_8
    );
    Files.writeString(source.resolve("references").resolve("guide.md"), "reference", StandardCharsets.UTF_8);

    Path workspaceSkills = tempDir.resolve("workspace").resolve("skills");
    Path staleTarget = workspaceSkills.resolve("renamed-skill");
    Files.createDirectories(staleTarget);
    Files.writeString(staleTarget.resolve("stale.txt"), "old", StandardCharsets.UTF_8);

    SkillFileSynchronizer synchronizer = new SkillFileSynchronizer();

    synchronizer.copySkill(source, workspaceSkills, "renamed-skill");

    Path target = workspaceSkills.resolve("renamed-skill");
    assertThat(target.resolve("stale.txt")).doesNotExist();
    assertThat(target.resolve("references").resolve("guide.md")).hasContent("reference");
    assertThat(Files.readString(target.resolve("SKILL.md")))
        .contains("name: renamed-skill")
        .contains("description: Original description.")
        .doesNotContain("name: original-skill");
  }
}
