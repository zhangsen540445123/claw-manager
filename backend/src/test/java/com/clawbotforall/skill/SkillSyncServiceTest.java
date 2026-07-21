package com.clawbotforall.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SkillSyncServiceTest {

  @TempDir
  Path tempDir;

  @Mock
  SkillMapper mapper;

  @Mock
  InstanceCommandService instanceCommandService;

  @Mock
  InstanceFileService fileService;

  ObjectMapper objectMapper = new ObjectMapper();

  SkillSyncService service;

  @BeforeEach
  void setUp() {
    service = new SkillSyncService(
        mapper,
        instanceCommandService,
        fileService,
        new SkillFileSynchronizer(),
        new ClawbotProperties(new ClawbotProperties.Paths(tempDir.toString()), null, null, null),
        Clock.fixed(Instant.parse("2026-07-05T12:00:00Z"), ZoneOffset.UTC)
    );
  }

  @Test
  void syncsSkillToSelectedInstancesAndKeepsPartialFailureLocal() throws Exception {
    Path source = tempDir.resolve("skill-repositories").resolve("repo_1").resolve("alpha-skill");
    Files.createDirectories(source);
    Files.writeString(
        source.resolve("SKILL.md"),
        """
        ---
        name: alpha-skill
        description: Alpha.
        ---

        Body.
        """,
        StandardCharsets.UTF_8
    );
    Path instanceBase = tempDir.resolve("instances").resolve("inst_ok");
    Path home = instanceBase.resolve("home");
    Path workspace = instanceBase.resolve("workspace");
    Files.createDirectories(home);
    objectMapper.writeValue(home.resolve("openclaw.json").toFile(), Map.of(
        "skills", Map.of(
            "load", Map.of("extraDirs", List.of("/existing/skills"))
        )
    ));
    InstanceEntity ok = new InstanceEntity();
    ok.setId("inst_ok");
    ok.setName("实例 A");

    SkillDefinitionEntity skill = skill("skill_1", "repo_1", "alpha-skill");
    when(mapper.findSkillById("skill_1")).thenReturn(skill);
    when(instanceCommandService.requireInstance("inst_ok")).thenReturn(ok);
    when(instanceCommandService.requireInstance("inst_missing"))
        .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "实例不存在。"));
    when(fileService.paths("inst_ok")).thenReturn(new InstancePaths(
        instanceBase,
        home,
        workspace,
        instanceBase.resolve("logs")
    ));

    SkillSyncResponse response = service.sync(new SkillSyncRequest(List.of(
        new SkillSyncItem("skill_1", List.of("inst_ok", "inst_missing"))
    )));

    assertThat(response.results()).hasSize(2);
    assertThat(response.results()).anySatisfy(result -> {
      assertThat(result.instanceId()).isEqualTo("inst_ok");
      assertThat(result.status()).isEqualTo("success");
      assertThat(result.instanceName()).isEqualTo("实例 A");
    });
    assertThat(response.results()).anySatisfy(result -> {
      assertThat(result.instanceId()).isEqualTo("inst_missing");
      assertThat(result.status()).isEqualTo("failed");
      assertThat(result.message()).isEqualTo("实例不存在。");
    });
    assertThat(workspace.resolve("skills").resolve("alpha-skill").resolve("SKILL.md")).exists();
    Map<String, Object> config = objectMapper.readValue(home.resolve("openclaw.json").toFile(), new TypeReference<>() {});
    assertThat(config.get("skills"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("load")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("extraDirs")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .containsExactly("/existing/skills", "/workspace/skills");
  }

  @Test
  void syncAddsSharedSkillLoadConfigWhenInstanceConfigHasNoSkillsSection() throws Exception {
    Path source = tempDir.resolve("skill-repositories").resolve("repo_1").resolve("alpha-skill");
    Files.createDirectories(source);
    Files.writeString(source.resolve("SKILL.md"), "---\nname: alpha-skill\ndescription: Alpha.\n---\n", StandardCharsets.UTF_8);
    Path instanceBase = tempDir.resolve("instances").resolve("inst_ok");
    Path home = instanceBase.resolve("home");
    Path workspace = instanceBase.resolve("workspace");
    Files.createDirectories(home);
    objectMapper.writeValue(home.resolve("openclaw.json").toFile(), Map.of(
        "gateway", Map.of("mode", "local"),
        "agents", Map.of("defaults", Map.of("workspace", "/workspace"))
    ));
    InstanceEntity ok = new InstanceEntity();
    ok.setId("inst_ok");
    ok.setName("实例 A");

    SkillDefinitionEntity skill = skill("skill_1", "repo_1", "alpha-skill");
    when(mapper.findSkillById("skill_1")).thenReturn(skill);
    when(instanceCommandService.requireInstance("inst_ok")).thenReturn(ok);
    when(fileService.paths("inst_ok")).thenReturn(new InstancePaths(
        instanceBase,
        home,
        workspace,
        instanceBase.resolve("logs")
    ));

    service.sync(new SkillSyncRequest(List.of(new SkillSyncItem("skill_1", List.of("inst_ok")))));

    Map<String, Object> config = objectMapper.readValue(home.resolve("openclaw.json").toFile(), new TypeReference<>() {});
    assertThat(config.get("skills"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extractingByKey("load")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("watch", true)
        .extractingByKey("extraDirs")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .containsExactly("/workspace/skills");
  }

  @Test
  void syncDoesNotRewriteConfigWhenSharedSkillLoadConfigAlreadyExists() throws Exception {
    Path source = tempDir.resolve("skill-repositories").resolve("repo_1").resolve("alpha-skill");
    Files.createDirectories(source);
    Files.writeString(source.resolve("SKILL.md"), "---\nname: alpha-skill\ndescription: Alpha.\n---\n", StandardCharsets.UTF_8);
    Path instanceBase = tempDir.resolve("instances").resolve("inst_ok");
    Path home = instanceBase.resolve("home");
    Path workspace = instanceBase.resolve("workspace");
    Files.createDirectories(home);
    Path configPath = home.resolve("openclaw.json");
    objectMapper.writeValue(configPath.toFile(), Map.of(
        "skills", Map.of(
            "load", Map.of(
                "extraDirs", List.of("/workspace/skills"),
                "watch", true
            )
        )
    ));
    FileTime originalModifiedTime = FileTime.from(Instant.parse("2026-07-01T00:00:00Z"));
    Files.setLastModifiedTime(configPath, originalModifiedTime);
    InstanceEntity ok = new InstanceEntity();
    ok.setId("inst_ok");
    ok.setName("实例 A");

    SkillDefinitionEntity skill = skill("skill_1", "repo_1", "alpha-skill");
    when(mapper.findSkillById("skill_1")).thenReturn(skill);
    when(instanceCommandService.requireInstance("inst_ok")).thenReturn(ok);
    when(fileService.paths("inst_ok")).thenReturn(new InstancePaths(
        instanceBase,
        home,
        workspace,
        instanceBase.resolve("logs")
    ));

    service.sync(new SkillSyncRequest(List.of(new SkillSyncItem("skill_1", List.of("inst_ok")))));

    assertThat(Files.getLastModifiedTime(configPath)).isEqualTo(originalModifiedTime);
  }

  private static SkillDefinitionEntity skill(String id, String repositoryId, String skillName) {
    SkillDefinitionEntity skill = new SkillDefinitionEntity();
    skill.setId(id);
    skill.setRepositoryId(repositoryId);
    skill.setSkillName(skillName);
    skill.setOriginalName(skillName);
    skill.setRelativePath(skillName);
    skill.setDescription("Alpha.");
    skill.setContentHash("hash");
    skill.setWarnings("[]");
    skill.setSyncable(true);
    skill.setLastCommitSha("commit123");
    return skill;
  }
}
