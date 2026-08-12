package com.clawbotforall.wechat;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WechatRebindOperationMapper {
  WechatRebindOperationEntity findByToken(@Param("bindToken") String bindToken);
  WechatRebindOperationEntity findByTokenForUpdate(@Param("bindToken") String bindToken);
  WechatRebindOperationEntity findActiveForUserForUpdate(@Param("phone") String phone, @Param("wechatUserId") String wechatUserId);
  List<WechatRebindOperationEntity> listByInstance(@Param("instanceId") String instanceId);
  int insert(WechatRebindOperationEntity operation);
  int update(WechatRebindOperationEntity operation);
  int redactByInstanceId(@Param("instanceId") String instanceId, @Param("updatedAt") String updatedAt);

  int redactForCleanup(
      @Param("phone") String phone,
      @Param("wechatUserId") String wechatUserId,
      @Param("accountId") String accountId,
      @Param("agentId") String agentId,
      @Param("updatedAt") String updatedAt
  );
}
