package com.clawbotforall.skill;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceCommandService;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.OpenClawSkillLoadConfig;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillSyncService {

  private final SkillMapper mapper;
  private final InstanceCommandService instanceCommandService;
  private final InstanceFileService fileService;
  private final SkillFileSynchronizer synchronizer;
  private final ClawbotProperties properties;
  private final Clock clock;
  private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  @Autowired
  public SkillSyncService(
      SkillMapper mapper,
      InstanceCommandService instanceCommandService,
      InstanceFileService fileService,
      SkillFileSynchronizer synchronizer,
      ClawbotProperties properties
  ) {
    this(mapper, instanceCommandService, fileService, synchronizer, properties, Clock.systemUTC());
  }

  SkillSyncService(
      SkillMapper mapper,
      InstanceCommandService instanceCommandService,
      InstanceFileService fileService,
      SkillFileSynchronizer synchronizer,
      ClawbotProperties properties,
      Clock clock
  ) {
    this.mapper = mapper;
    this.instanceCommandService = instanceCommandService;
    this.fileService = fileService;
    this.synchronizer = synchronizer;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public SkillSyncResponse sync(SkillSyncRequest request) {
    List<SkillSyncResult> results = new ArrayList<>();
    List<SkillSyncItem> items = request == null || request.items() == null ? List.of() : request.items();
    for (SkillSyncItem item : items) {
      List<String> instanceIds = item.instanceIds() == null ? List.of() : item.instanceIds();
      SkillDefinitionEntity skill = mapper.findSkillById(defaultString(item.skillId()));
      for (String instanceId : instanceIds) {
        results.add(syncOne(skill, defaultString(item.skillId()), defaultString(instanceId)));
      }
    }
    return new SkillSyncResponse(results);
  }

  private SkillSyncResult syncOne(SkillDefinitionEntity skill, String requestedSkillId, String instanceId) {
    InstanceEntity instance = null;
    String now = now();
    try {
      if (skill == null) {
        throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Skill 不存在。");
      }
      if (!skill.isSyncable()) {
        throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Skill 不可同步。");
      }
      instance = instanceCommandService.requireInstance(instanceId);
      Path sourceDir = sourcePath(skill);
      InstancePaths paths = fileService.paths(instance.getId());
      synchronizer.copySkill(sourceDir, paths.workspaceDir().resolve("skills"), skill.getSkillName());
      ensureSharedSkillLoadConfig(paths);
      mapper.upsertInstanceSync(syncEntity(instance.getId(), skill, "success", "同步完成。", now, now));
      return new SkillSyncResult(
          skill.getId(),
          skill.getSkillName(),
          instance.getId(),
          defaultString(instance.getName()),
          "success",
          "同步完成。",
          now
      );
    } catch (RuntimeException error) {
      String message = error.getMessage() == null ? "同步失败。" : error.getMessage();
      if (instance != null && skill != null) {
        mapper.upsertInstanceSync(syncEntity(instance.getId(), skill, "failed", message, null, now));
      }
      return new SkillSyncResult(
          skill == null ? requestedSkillId : skill.getId(),
          skill == null ? "" : skill.getSkillName(),
          instanceId,
          instance == null ? "" : defaultString(instance.getName()),
          "failed",
          message,
          ""
      );
    }
  }

  private void ensureSharedSkillLoadConfig(InstancePaths paths) {
    try {
      OpenClawSkillLoadConfig.ensureConfigFile(paths.homeDir().resolve("openclaw.json"), objectMapper);
    } catch (IOException error) {
      throw new UncheckedIOException("写入 OpenClaw Skill 共享配置失败。", error);
    }
  }

  private SkillInstanceSyncEntity syncEntity(
      String instanceId,
      SkillDefinitionEntity skill,
      String status,
      String message,
      String syncedAt,
      String updatedAt
  ) {
    SkillInstanceSyncEntity entity = new SkillInstanceSyncEntity();
    entity.setInstanceId(instanceId);
    entity.setSkillName(skill.getSkillName());
    entity.setSkillId(skill.getId());
    entity.setRepositoryId(skill.getRepositoryId());
    entity.setSourceCommitSha(skill.getLastCommitSha());
    entity.setStatus(status);
    entity.setMessage(message);
    entity.setSyncedAt(syncedAt);
    entity.setUpdatedAt(updatedAt);
    return entity;
  }

  private Path sourcePath(SkillDefinitionEntity skill) {
    Path repositoryRoot = Path.of(properties.paths().dataDir(), "skill-repositories", skill.getRepositoryId()).normalize();
    Path source = repositoryRoot.resolve(skill.getRelativePath()).normalize();
    if (!source.startsWith(repositoryRoot)) {
      throw new IllegalArgumentException("Skill 来源路径无效。");
    }
    return source;
  }

  private String now() {
    return clock.instant().toString();
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
