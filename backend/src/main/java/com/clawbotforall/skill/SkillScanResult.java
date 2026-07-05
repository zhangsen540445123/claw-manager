package com.clawbotforall.skill;

import java.util.List;

public record SkillScanResult(
    String repositoryId,
    String skillName,
    String originalName,
    String relativePath,
    String description,
    String contentHash,
    boolean syncable,
    List<String> warnings,
    String commitSha
) {}
