package com.clawbotforall.skill;

public record SkillRepositoryRequest(
    String name,
    String repoUrl,
    String branch,
    String authType,
    String accessToken
) {}
