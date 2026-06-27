package com.clawbotforall.externalapi;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExternalApiUserRouteMapper {
  ExternalApiUserRouteEntity findByOpenidHash(@Param("openidHash") String openidHash);

  List<ExternalApiUserRouteEntity> list(
      @Param("keyword") String keyword,
      @Param("instanceId") String instanceId,
      @Param("limit") int limit,
      @Param("offset") int offset
  );

  int count(@Param("keyword") String keyword, @Param("instanceId") String instanceId);

  int countByInstanceId(@Param("instanceId") String instanceId);

  int insert(ExternalApiUserRouteEntity route);

  int updateLastUsed(@Param("openidHash") String openidHash, @Param("lastUsedAt") String lastUsedAt);

  int updateInstance(
      @Param("openidHash") String openidHash,
      @Param("instanceId") String instanceId,
      @Param("updatedAt") String updatedAt
  );
}
