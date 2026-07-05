package com.clawbotforall.skill;

import java.util.List;

public record PublicSkillDefinition(
    String id,
    String repositoryId,
    String repositoryName,
    String skillName,
    String originalName,
    String relativePath,
    String description,
    String contentHash,
    List<String> warnings,
    boolean syncable,
    String lastCommitSha,
    String createdAt,
    String updatedAt
) {}
