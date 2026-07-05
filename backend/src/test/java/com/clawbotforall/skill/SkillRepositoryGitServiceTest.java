package com.clawbotforall.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillRepositoryGitServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void clonesAndPullsLocalGitRepository() throws Exception {
    Path source = tempDir.resolve("source");
    Files.createDirectories(source.resolve("alpha-skill"));
    Files.writeString(
        source.resolve("alpha-skill").resolve("SKILL.md"),
        """
        ---
        name: alpha-skill
        description: Alpha.
        ---
        """,
        StandardCharsets.UTF_8
    );
    try (Git git = Git.init().setInitialBranch("main").setDirectory(source.toFile()).call()) {
      git.add().addFilepattern(".").call();
      git.commit()
          .setAuthor("Tester", "tester@example.com")
          .setCommitter("Tester", "tester@example.com")
          .setMessage("initial")
          .call();
    }

    SkillRepositoryEntity repository = new SkillRepositoryEntity();
    repository.setRepoUrl(source.toUri().toString());
    repository.setBranch("main");
    repository.setAuthType("none");
    Path target = tempDir.resolve("target");
    SkillRepositoryGitService service = new SkillRepositoryGitService();

    SkillPullResult first = service.pull(repository, target);

    assertThat(first.commitSha()).isNotBlank();
    assertThat(target.resolve("alpha-skill").resolve("SKILL.md")).exists();

    Files.writeString(source.resolve("alpha-skill").resolve("README.md"), "updated", StandardCharsets.UTF_8);
    try (Git git = Git.open(source.toFile())) {
      git.add().addFilepattern(".").call();
      git.commit()
          .setAuthor("Tester", "tester@example.com")
          .setCommitter("Tester", "tester@example.com")
          .setMessage("update")
          .call();
    }

    SkillPullResult second = service.pull(repository, target);

    assertThat(second.commitSha()).isNotEqualTo(first.commitSha());
    assertThat(target.resolve("alpha-skill").resolve("README.md")).hasContent("updated");
  }
}
