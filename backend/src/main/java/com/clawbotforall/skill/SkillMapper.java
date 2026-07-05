package com.clawbotforall.skill;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkillMapper {

  List<SkillRepositoryEntity> listRepositories();

  SkillRepositoryEntity findRepositoryById(@Param("id") String id);

  int insertRepository(SkillRepositoryEntity repository);

  int updateRepository(SkillRepositoryEntity repository);

  int deleteRepository(@Param("id") String id);

  int updateRepositoryPull(
      @Param("id") String id,
      @Param("lastCommitSha") String lastCommitSha,
      @Param("lastPullStatus") String lastPullStatus,
      @Param("lastPullMessage") String lastPullMessage,
      @Param("lastPulledAt") String lastPulledAt,
      @Param("updatedAt") String updatedAt
  );

  List<SkillDefinitionEntity> listSkills();

  List<SkillDefinitionEntity> listSkillsByRepositoryId(@Param("repositoryId") String repositoryId);

  SkillDefinitionEntity findSkillById(@Param("id") String id);

  int updateSkillName(
      @Param("id") String id,
      @Param("skillName") String skillName,
      @Param("updatedAt") String updatedAt
  );

  int deleteDefinitionsByRepositoryId(@Param("repositoryId") String repositoryId);

  int insertDefinitions(@Param("definitions") List<SkillDefinitionEntity> definitions);

  default void replaceDefinitions(String repositoryId, List<SkillDefinitionEntity> definitions) {
    deleteDefinitionsByRepositoryId(repositoryId);
    if (definitions != null && !definitions.isEmpty()) {
      insertDefinitions(definitions);
    }
  }

  List<SkillInstanceSyncEntity> listInstanceSyncs();

  int upsertInstanceSync(SkillInstanceSyncEntity sync);
}
