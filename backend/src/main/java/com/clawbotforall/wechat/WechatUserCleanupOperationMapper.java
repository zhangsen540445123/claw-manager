package com.clawbotforall.wechat;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WechatUserCleanupOperationMapper {
  WechatUserCleanupOperationEntity findById(@Param("operationId") String operationId);
  WechatUserCleanupOperationEntity findByIdForUpdate(@Param("operationId") String operationId);
  WechatUserCleanupOperationEntity findActiveBySubjectForUpdate(
      @Param("instanceId") String instanceId, @Param("subjectHash") String subjectHash);
  WechatUserCleanupOperationEntity findActiveByIdentityForUpdate(
      @Param("instanceId") String instanceId,
      @Param("phone") String phone,
      @Param("wechatUserId") String wechatUserId,
      @Param("accountId") String accountId,
      @Param("agentId") String agentId);
  List<WechatUserCleanupOperationEntity> listByInstance(@Param("instanceId") String instanceId);
  List<WechatUserCleanupOperationEntity> listActive();
  int insert(WechatUserCleanupOperationEntity operation);
  int update(WechatUserCleanupOperationEntity operation);
}
