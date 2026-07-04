package com.clawbotforall.miniapp;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MiniappClientMapper {
  MiniappClientEntity findByAppId(@Param("appId") String appId);

  List<MiniappClientEntity> list();

  int insert(MiniappClientEntity client);

  int updateEnabled(
      @Param("appId") String appId,
      @Param("enabled") boolean enabled,
      @Param("updatedAt") String updatedAt
  );

  int updateSecret(
      @Param("appId") String appId,
      @Param("appSecret") String appSecret,
      @Param("updatedAt") String updatedAt
  );

  int deleteByAppId(@Param("appId") String appId);
}
