package com.clawbotforall.skill;

public record PublicSkillRepository(
    String id,
    String name,
    String repoUrl,
    String branch,
    String authType,
    String accessToken,
    String tokenPreview,
    boolean hasToken,
    String lastCommitSha,
    String lastPullStatus,
    String lastPullMessage,
    String lastPulledAt,
    String createdAt,
    String updatedAt
) {}
