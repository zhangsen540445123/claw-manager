package com.clawbotforall.wechat;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WechatRebindOperationMapper {
  WechatRebindOperationEntity findByToken(@Param("bindToken") String bindToken);
  WechatRebindOperationEntity findByTokenForUpdate(@Param("bindToken") String bindToken);
  WechatRebindOperationEntity findActiveForUserForUpdate(@Param("phone") String phone, @Param("wechatUserId") String wechatUserId);
  int insert(WechatRebindOperationEntity operation);
  int update(WechatRebindOperationEntity operation);
}
