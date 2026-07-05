package com.clawbotforall.skill;

public record SkillSyncResult(
    String skillId,
    String skillName,
    String instanceId,
    String instanceName,
    String status,
    String message,
    String syncedAt
) {}
