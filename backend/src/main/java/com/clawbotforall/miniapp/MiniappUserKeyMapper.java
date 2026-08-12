package com.clawbotforall.miniapp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MiniappUserKeyMapper {
  MiniappUserKeyEntity findByOpenidHash(@Param("openidHash") String openidHash);

  MiniappUserKeyEntity findByUserKey(@Param("userKey") String userKey);

  int insert(MiniappUserKeyEntity key);

  int replaceKey(MiniappUserKeyEntity key);

  int updateLastUsed(@Param("openidHash") String openidHash, @Param("lastUsedAt") String lastUsedAt);

  int deleteByAgentId(@Param("agentId") String agentId);

  int deleteByInstanceId(@Param("instanceId") String instanceId);
}
