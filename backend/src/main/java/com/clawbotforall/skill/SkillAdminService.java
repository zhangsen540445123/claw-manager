package com.clawbotforall.skill;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.web.ApiException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillAdminService {

  private static final Logger log = LoggerFactory.getLogger(SkillAdminService.class);

  private final SkillMapper mapper;
  private final SkillRepositoryGitService gitService;
  private final SkillScanner scanner;
  private final ClawbotProperties properties;
  private final Clock clock;
  private final SkillRepositoryIdGenerator idGenerator;

  @Autowired
  public SkillAdminService(
      SkillMapper mapper,
      SkillRepositoryGitService gitService,
      SkillScanner scanner,
      ClawbotProperties properties
  ) {
    this(mapper, gitService, scanner, properties, Clock.systemUTC(), SkillAdminService::randomRepositoryId);
  }

  SkillAdminService(
      SkillMapper mapper,
      SkillRepositoryGitService gitService,
      SkillScanner scanner,
      ClawbotProperties properties,
      Clock clock,
      SkillRepositoryIdGenerator idGenerator
  ) {
    this.mapper = mapper;
    this.gitService = gitService;
    this.scanner = scanner;
    this.properties = properties;
    this.clock = clock;
    this.idGenerator = idGenerator;
  }

  @Transactional(readOnly = true)
  public List<PublicSkillRepository> listRepositories() {
    return mapper.listRepositories().stream()
        .map(this::toPublicRepository)
        .toList();
  }

  @Transactional
  public PublicSkillRepository createRepository(SkillRepositoryRequest request) {
    SkillRepositoryEntity entity = normalizeRepository(new SkillRepositoryEntity(), request, true);
    entity.setId(idGenerator.generate());
    String now = now();
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    entity.setLastPullStatus("never");
    mapper.insertRepository(entity);
    return toPublicRepository(entity);
  }

  @Transactional
  public PublicSkillRepository updateRepository(String repositoryId, SkillRepositoryRequest request) {
    SkillRepositoryEntity existing = requireRepository(repositoryId);
    SkillRepositoryEntity normalized = normalizeRepository(existing, request, false);
    normalized.setUpdatedAt(now());
    mapper.updateRepository(normalized);
    return toPublicRepository(normalized);
  }

  @Transactional
  public void deleteRepository(String repositoryId) {
    if (mapper.deleteRepository(repositoryId) == 0) {
      throw new ApiException(HttpStatus.NOT_FOUND, "Skill 仓库不存在。");
    }
  }

  @Transactional
  public PublicSkillRepository pullRepository(String repositoryId) {
    SkillRepositoryEntity repository = requireRepository(repositoryId);
    String now = now();
    try {
      Path repositoryDir = repositoryPath(repository.getId());
      SkillPullResult pull = gitService.pull(repository, repositoryDir);
      Map<String, SkillDefinitionEntity> existingByPath = existingSkillsByPath(repository.getId());
      List<SkillDefinitionEntity> definitions = scanner.scan(repository.getId(), repositoryDir, pull.commitSha()).stream()
          .map(result -> toDefinition(result, now, existingByPath.get(result.relativePath())))
          .toList();
      mapper.replaceDefinitions(repository.getId(), definitions);
      mapper.updateRepositoryPull(repository.getId(), pull.commitSha(), "success", pull.message(), now, now);
      repository.setLastCommitSha(pull.commitSha());
      repository.setLastPullStatus("success");
      repository.setLastPullMessage(pull.message());
      repository.setLastPulledAt(now);
      repository.setUpdatedAt(now);
      log.info("Skill 仓库拉取完成：repositoryId={}, skillCount={}, commit={}", repository.getId(), definitions.size(), pull.commitSha());
      return toPublicRepository(repository);
    } catch (RuntimeException error) {
      String message = error.getMessage() == null ? "拉取 Skill 仓库失败。" : error.getMessage();
      mapper.updateRepositoryPull(repository.getId(), repository.getLastCommitSha(), "failed", message, now, now);
      throw new ApiException(HttpStatus.BAD_GATEWAY, message);
    }
  }

  @Transactional(readOnly = true)
  public List<PublicSkillDefinition> listSkills() {
    return mapper.listSkills().stream()
        .map(this::toPublicSkill)
        .toList();
  }

  @Transactional
  public PublicSkillDefinition updateSkillName(String skillId, SkillNameUpdateRequest request) {
    String skillName = sanitizeSkillName(request == null ? "" : request.skillName());
    SkillDefinitionEntity existing = mapper.findSkillById(skillId);
    if (existing == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "Skill 不存在。");
    }
    String now = now();
    mapper.updateSkillName(skillId, skillName, now);
    existing.setSkillName(skillName);
    existing.setUpdatedAt(now);
    return toPublicSkill(existing);
  }

  @Transactional(readOnly = true)
  public List<PublicSkillInstanceSync> listInstanceSyncs() {
    return mapper.listInstanceSyncs().stream()
        .map(this::toPublicSync)
        .toList();
  }

  Path repositoryPath(String repositoryId) {
    return Path.of(properties.paths().dataDir(), "skill-repositories", repositoryId);
  }

  private SkillRepositoryEntity normalizeRepository(
      SkillRepositoryEntity target,
      SkillRepositoryRequest request,
      boolean creating
  ) {
    SkillRepositoryRequest body = request == null ? new SkillRepositoryRequest("", "", "", "", "") : request;
    target.setName(requiredText(body.name(), "仓库名称不能为空。"));
    target.setRepoUrl(requiredText(body.repoUrl(), "GitHub 仓库 URL 不能为空。"));
    target.setBranch(defaultString(body.branch()).isBlank() ? "main" : body.branch().trim());
    String authType = normalizeAuthType(body.authType(), body.accessToken(), creating ? "" : target.getAuthType());
    target.setAuthType(authType);
    if ("none".equals(authType)) {
      target.setAccessToken(null);
      target.setTokenPreview(null);
    } else {
      String token = defaultString(body.accessToken()).trim();
      if (token.isBlank() && !creating) {
        token = defaultString(target.getAccessToken()).trim();
      }
      if (token.isBlank()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "私有仓库 Token 不能为空。");
      }
      target.setAccessToken(token);
      target.setTokenPreview(preview(token));
    }
    return target;
  }

  private SkillDefinitionEntity toDefinition(
      SkillScanResult result,
      String now,
      SkillDefinitionEntity existing
  ) {
    SkillDefinitionEntity entity = new SkillDefinitionEntity();
    entity.setId(existing == null ? randomSkillId() : existing.getId());
    entity.setRepositoryId(result.repositoryId());
    entity.setSkillName(existing == null ? result.skillName() : existing.getSkillName());
    entity.setOriginalName(result.originalName());
    entity.setRelativePath(result.relativePath());
    entity.setDescription(result.description());
    entity.setContentHash(result.contentHash());
    entity.setWarnings(toJsonArray(result.warnings()));
    entity.setSyncable(result.syncable());
    entity.setLastCommitSha(result.commitSha());
    entity.setCreatedAt(existing == null ? now : existing.getCreatedAt());
    entity.setUpdatedAt(now);
    return entity;
  }

  private Map<String, SkillDefinitionEntity> existingSkillsByPath(String repositoryId) {
    Map<String, SkillDefinitionEntity> result = new HashMap<>();
    for (SkillDefinitionEntity skill : mapper.listSkillsByRepositoryId(repositoryId)) {
      result.put(skill.getRelativePath(), skill);
    }
    return result;
  }

  private PublicSkillRepository toPublicRepository(SkillRepositoryEntity entity) {
    return new PublicSkillRepository(
        defaultString(entity.getId()),
        defaultString(entity.getName()),
        defaultString(entity.getRepoUrl()),
        defaultString(entity.getBranch()),
        defaultString(entity.getAuthType()),
        null,
        defaultString(entity.getTokenPreview()),
        !defaultString(entity.getAccessToken()).isBlank(),
        defaultString(entity.getLastCommitSha()),
        defaultString(entity.getLastPullStatus()),
        defaultString(entity.getLastPullMessage()),
        defaultString(entity.getLastPulledAt()),
        defaultString(entity.getCreatedAt()),
        defaultString(entity.getUpdatedAt())
    );
  }

  private PublicSkillDefinition toPublicSkill(SkillDefinitionEntity entity) {
    return new PublicSkillDefinition(
        defaultString(entity.getId()),
        defaultString(entity.getRepositoryId()),
        defaultString(entity.getRepositoryName()),
        defaultString(entity.getSkillName()),
        defaultString(entity.getOriginalName()),
        defaultString(entity.getRelativePath()),
        defaultString(entity.getDescription()),
        defaultString(entity.getContentHash()),
        parseJsonArray(entity.getWarnings()),
        entity.isSyncable(),
        defaultString(entity.getLastCommitSha()),
        defaultString(entity.getCreatedAt()),
        defaultString(entity.getUpdatedAt())
    );
  }

  private PublicSkillInstanceSync toPublicSync(SkillInstanceSyncEntity entity) {
    return new PublicSkillInstanceSync(
        defaultString(entity.getInstanceId()),
        defaultString(entity.getSkillName()),
        defaultString(entity.getSkillId()),
        defaultString(entity.getRepositoryId()),
        defaultString(entity.getSourceCommitSha()),
        defaultString(entity.getStatus()),
        defaultString(entity.getMessage()),
        defaultString(entity.getSyncedAt()),
        defaultString(entity.getUpdatedAt())
    );
  }

  private SkillRepositoryEntity requireRepository(String repositoryId) {
    SkillRepositoryEntity existing = mapper.findRepositoryById(repositoryId);
    if (existing == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "Skill 仓库不存在。");
    }
    return existing;
  }

  private String now() {
    return clock.instant().toString();
  }

  static String preview(String value) {
    String normalized = defaultString(value);
    if (normalized.isBlank() || normalized.length() <= 16) {
      return normalized;
    }
    return normalized.substring(0, 11) + "..." + normalized.substring(normalized.length() - 4);
  }

  private static String sanitizeSkillName(String value) {
    String name = defaultString(value).trim();
    if (name.isBlank() || name.length() > 120 || name.contains("/") || name.contains("\\") || name.contains("..")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Skill 名称无效。");
    }
    return name;
  }

  private static String normalizeAuthType(String value, String accessToken, String existing) {
    String normalized = defaultString(value).trim();
    if (normalized.isBlank()) {
      normalized = defaultString(accessToken).isBlank() ? defaultString(existing) : "token";
    }
    if (normalized.isBlank()) {
      normalized = "none";
    }
    if (!normalized.equals("none") && !normalized.equals("token")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "仓库认证方式无效。");
    }
    return normalized;
  }

  private static String requiredText(String value, String message) {
    String text = defaultString(value).trim();
    if (text.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, message);
    }
    return text;
  }

  private static String toJsonArray(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "[]";
    }
    List<String> escaped = values.stream()
        .map(value -> "\"" + defaultString(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .toList();
    return "[" + String.join(",", escaped) + "]";
  }

  private static List<String> parseJsonArray(String raw) {
    String value = defaultString(raw).trim();
    if (value.length() < 2 || !value.startsWith("[") || !value.endsWith("]")) {
      return List.of();
    }
    String inner = value.substring(1, value.length() - 1).trim();
    if (inner.isBlank()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inString = false;
    boolean escaping = false;
    for (int index = 0; index < inner.length(); index++) {
      char item = inner.charAt(index);
      if (escaping) {
        current.append(item);
        escaping = false;
        continue;
      }
      if (item == '\\') {
        escaping = true;
        continue;
      }
      if (item == '"') {
        inString = !inString;
        continue;
      }
      if (item == ',' && !inString) {
        result.add(current.toString());
        current = new StringBuilder();
        continue;
      }
      current.append(item);
    }
    result.add(current.toString());
    return result.stream().map(String::trim).filter(item -> !item.isBlank()).toList();
  }

  private static String randomRepositoryId() {
    return "repo_" + Long.toString(System.currentTimeMillis(), 36)
        + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
  }

  private static String randomSkillId() {
    return "skill_" + Long.toString(System.currentTimeMillis(), 36)
        + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
