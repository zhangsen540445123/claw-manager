package com.clawbotforall.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clawbotforall.config.ClawbotProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillAdminServiceTest {

  @Mock
  SkillMapper mapper;

  @Mock
  SkillRepositoryGitService gitService;

  @Mock
  SkillScanner scanner;

  SkillAdminService service;

  @BeforeEach
  void setUp() {
    service = new SkillAdminService(
        mapper,
        gitService,
        scanner,
        new ClawbotProperties(new ClawbotProperties.Paths("data"), null, null, null),
        Clock.fixed(Instant.parse("2026-07-05T12:00:00Z"), ZoneOffset.UTC),
        () -> "repo_fixed"
    );
  }

  @Test
  void createsRepositoryWithoutReturningPlainToken() {
    PublicSkillRepository repository = service.createRepository(new SkillRepositoryRequest(
        "能力仓库",
        "https://github.com/example/skills.git",
        "main",
        "token",
        "ghp_abcdefghijklmnopqrstuvwxyz"
    ));

    ArgumentCaptor<SkillRepositoryEntity> captor = ArgumentCaptor.forClass(SkillRepositoryEntity.class);
    verify(mapper).insertRepository(captor.capture());
    assertThat(captor.getValue().getId()).isEqualTo("repo_fixed");
    assertThat(captor.getValue().getAccessToken()).isEqualTo("ghp_abcdefghijklmnopqrstuvwxyz");
    assertThat(captor.getValue().getTokenPreview()).isEqualTo("ghp_abcdefg...wxyz");
    assertThat(repository.accessToken()).isNull();
    assertThat(repository.tokenPreview()).isEqualTo("ghp_abcdefg...wxyz");
    assertThat(repository.hasToken()).isTrue();
  }

  @Test
  void pullRepositoryScansSkillsAndStoresDefinitions() {
    SkillRepositoryEntity existing = repository("repo_1");
    when(mapper.findRepositoryById("repo_1")).thenReturn(existing);
    when(gitService.pull(eq(existing), any(Path.class))).thenReturn(new SkillPullResult("commit123", "已拉取"));
    when(scanner.scan(eq("repo_1"), any(Path.class), eq("commit123")))
        .thenReturn(List.of(new SkillScanResult(
            "repo_1",
            "alpha-skill",
            "alpha-skill",
            "packs/alpha-skill",
            "Alpha description.",
            "hash123",
            true,
            List.of(),
            "commit123"
        )));

    service.pullRepository("repo_1");

    ArgumentCaptor<List<SkillDefinitionEntity>> captor = ArgumentCaptor.captor();
    verify(mapper).replaceDefinitions(eq("repo_1"), captor.capture());
    assertThat(captor.getValue()).hasSize(1);
    assertThat(captor.getValue().getFirst().getId()).startsWith("skill_");
    assertThat(captor.getValue().getFirst().getSkillName()).isEqualTo("alpha-skill");
    verify(mapper).updateRepositoryPull(
        "repo_1",
        "commit123",
        "success",
        "已拉取",
        "2026-07-05T12:00:00Z",
        "2026-07-05T12:00:00Z"
    );
  }

  @Test
  void pullRepositoryPreservesEditedSkillNameForSameRelativePath() {
    SkillRepositoryEntity existing = repository("repo_1");
    SkillDefinitionEntity edited = new SkillDefinitionEntity();
    edited.setId("skill_existing");
    edited.setRepositoryId("repo_1");
    edited.setSkillName("renamed-alpha");
    edited.setOriginalName("alpha-skill");
    edited.setRelativePath("packs/alpha-skill");
    when(mapper.findRepositoryById("repo_1")).thenReturn(existing);
    when(mapper.listSkillsByRepositoryId("repo_1")).thenReturn(List.of(edited));
    when(gitService.pull(eq(existing), any(Path.class))).thenReturn(new SkillPullResult("commit456", "已拉取"));
    when(scanner.scan(eq("repo_1"), any(Path.class), eq("commit456")))
        .thenReturn(List.of(new SkillScanResult(
            "repo_1",
            "alpha-skill",
            "alpha-skill",
            "packs/alpha-skill",
            "Updated description.",
            "hash456",
            true,
            List.of(),
            "commit456"
        )));

    service.pullRepository("repo_1");

    ArgumentCaptor<List<SkillDefinitionEntity>> captor = ArgumentCaptor.captor();
    verify(mapper).replaceDefinitions(eq("repo_1"), captor.capture());
    assertThat(captor.getValue().getFirst().getId()).isEqualTo("skill_existing");
    assertThat(captor.getValue().getFirst().getSkillName()).isEqualTo("renamed-alpha");
    assertThat(captor.getValue().getFirst().getContentHash()).isEqualTo("hash456");
  }

  private static SkillRepositoryEntity repository(String id) {
    SkillRepositoryEntity repository = new SkillRepositoryEntity();
    repository.setId(id);
    repository.setName("能力仓库");
    repository.setRepoUrl("https://github.com/example/skills.git");
    repository.setBranch("main");
    repository.setAuthType("none");
    repository.setCreatedAt("2026-07-05T10:00:00Z");
    repository.setUpdatedAt("2026-07-05T10:00:00Z");
    return repository;
  }
}
