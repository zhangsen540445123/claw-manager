package com.clawbotforall.miniapp;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MiniappUserBindingMapper {
  MiniappUserBindingEntity findByOpenidHash(@Param("openidHash") String openidHash);

  MiniappUserBindingEntity findByOpenidHashForUpdate(@Param("openidHash") String openidHash);

  MiniappUserBindingEntity findByWechatUserId(@Param("wechatUserId") String wechatUserId);

  List<MiniappWechatBindingSummary> listWechatSummariesByInstanceIds(@Param("instanceIds") List<String> instanceIds);

  int countByInstanceId(@Param("instanceId") String instanceId);

  List<MiniappUserBindingEntity> listByInstanceId(@Param("instanceId") String instanceId);

  List<MiniappUserBindingEntity> listByAgentId(@Param("agentId") String agentId);

  int deleteByAgentId(@Param("agentId") String agentId);

  int deleteByInstanceId(@Param("instanceId") String instanceId);

  int insert(MiniappUserBindingEntity binding);

  int updateBindToken(
      @Param("openidHash") String openidHash,
      @Param("currentBindToken") String currentBindToken,
      @Param("updatedAt") String updatedAt
  );

  int updateBindTokenPreservingStatus(
      @Param("openidHash") String openidHash,
      @Param("currentBindToken") String currentBindToken,
      @Param("updatedAt") String updatedAt
  );

  int markConnected(
      @Param("openidHash") String openidHash,
      @Param("wechatUserId") String wechatUserId,
      @Param("agentId") String agentId,
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
