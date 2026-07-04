package com.clawbotforall.miniapp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MiniappUserBindingMapper {
  MiniappUserBindingEntity findByOpenidHash(@Param("openidHash") String openidHash);

  int countByInstanceId(@Param("instanceId") String instanceId);

  int insert(MiniappUserBindingEntity binding);

  int updateBindToken(
      @Param("openidHash") String openidHash,
      @Param("currentBindToken") String currentBindToken,
      @Param("updatedAt") String updatedAt
  );

  int markConnected(
      @Param("openidHash") String openidHash,
      @Param("wechatUserId") String wechatUserId,
      @Param("openvikingUserId") String openvikingUserId,
      @Param("boundAt") String boundAt,
      @Param("updatedAt") String updatedAt
  );

  int updateStatus(
      @Param("openidHash") String openidHash,
      @Param("bindStatus") String bindStatus,
      @Param("updatedAt") String updatedAt
  );
}
