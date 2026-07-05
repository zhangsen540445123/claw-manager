package com.clawbotforall.skill;

public record PublicSkillInstanceSync(
    String instanceId,
    String skillName,
    String skillId,
    String repositoryId,
    String sourceCommitSha,
    String status,
    String message,
    String syncedAt,
    String updatedAt
) {}
