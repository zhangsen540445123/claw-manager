package com.clawbotforall.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

@Service
public class SkillRepositoryGitService {

  public SkillPullResult pull(SkillRepositoryEntity repository, Path targetDir) {
    try {
      Files.createDirectories(targetDir.getParent());
      boolean existingRepository = Files.exists(targetDir.resolve(".git"));
      Git git = openOrClone(repository, targetDir);
      try (git) {
        String branch = defaultBranch(repository);
        if (existingRepository) {
          checkoutBranch(git, branch);
          git.pull()
              .setCredentialsProvider(credentials(repository))
              .call();
        }
        ObjectId head = git.getRepository().resolve("HEAD");
        String commitSha = head == null ? "" : head.name();
        return new SkillPullResult(commitSha, "仓库已拉取。");
      }
    } catch (IOException | GitAPIException error) {
      throw new IllegalStateException("拉取 Skill 仓库失败：" + error.getMessage(), error);
    }
  }

  private Git openOrClone(SkillRepositoryEntity repository, Path targetDir) throws IOException, GitAPIException {
    if (Files.exists(targetDir.resolve(".git"))) {
      return Git.open(targetDir.toFile());
    }
    deleteRecursively(targetDir);
    return Git.cloneRepository()
        .setURI(repository.getRepoUrl())
        .setDirectory(targetDir.toFile())
        .setBranch(defaultBranch(repository))
        .setCredentialsProvider(credentials(repository))
        .call();
  }

  private void checkoutBranch(Git git, String branch) throws GitAPIException {
    git.checkout()
        .setName(branch)
        .call();
  }

  private CredentialsProvider credentials(SkillRepositoryEntity repository) {
    if (!"token".equals(defaultString(repository.getAuthType())) || defaultString(repository.getAccessToken()).isBlank()) {
      return null;
    }
    return new UsernamePasswordCredentialsProvider("x-access-token", repository.getAccessToken());
  }

  private static String defaultBranch(SkillRepositoryEntity repository) {
    String branch = defaultString(repository.getBranch()).trim();
    return branch.isBlank() ? "main" : branch;
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (var stream = Files.walk(path)) {
      for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(item);
      }
    }
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
