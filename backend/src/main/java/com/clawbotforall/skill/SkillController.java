package com.clawbotforall.skill;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SkillController {

  private final SkillAdminService adminService;
  private final SkillSyncService syncService;

  public SkillController(SkillAdminService adminService, SkillSyncService syncService) {
    this.adminService = adminService;
    this.syncService = syncService;
  }

  @GetMapping("/api/admin/skill-repositories")
  public Map<String, Object> listRepositories(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of("repositories", adminService.listRepositories());
  }

  @PostMapping("/api/admin/skill-repositories")
  public Map<String, Object> createRepository(
      @RequestBody(required = false) SkillRepositoryRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("repository", adminService.createRepository(request));
  }

  @PatchMapping("/api/admin/skill-repositories/{repositoryId}")
  public Map<String, Object> updateRepository(
      @PathVariable String repositoryId,
      @RequestBody(required = false) SkillRepositoryRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("repository", adminService.updateRepository(repositoryId, request));
  }

  @DeleteMapping("/api/admin/skill-repositories/{repositoryId}")
  public Map<String, Object> deleteRepository(
      @PathVariable String repositoryId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    adminService.deleteRepository(repositoryId);
    return Map.of("ok", true);
  }

  @PostMapping("/api/admin/skill-repositories/{repositoryId}/pull")
  public Map<String, Object> pullRepository(
      @PathVariable String repositoryId,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("repository", adminService.pullRepository(repositoryId));
  }

  @GetMapping("/api/admin/skills")
  public Map<String, Object> listSkills(Authentication authentication) {
    requireAdmin(authentication);
    return Map.of(
        "skills", adminService.listSkills(),
        "syncs", adminService.listInstanceSyncs()
    );
  }

  @PatchMapping("/api/admin/skills/{skillId}")
  public Map<String, Object> updateSkill(
      @PathVariable String skillId,
      @RequestBody(required = false) SkillNameUpdateRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return Map.of("skill", adminService.updateSkillName(skillId, request));
  }

  @PostMapping("/api/admin/skills/sync")
  public SkillSyncResponse syncSkills(
      @RequestBody(required = false) SkillSyncRequest request,
      Authentication authentication
  ) {
    requireAdmin(authentication);
    return syncService.sync(request);
  }

  private static void requireAdmin(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAdmin)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录。");
    }
  }
}
