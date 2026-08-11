package com.clawbotforall.useragent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserAgentIdentityMapper {
  UserAgentIdentityEntity findByWechatUserId(@Param("wechatUserId") String wechatUserId);

  UserAgentIdentityEntity findByWechatUserIdForUpdate(@Param("wechatUserId") String wechatUserId);

  int insert(UserAgentIdentityEntity identity);

  int deleteByAgentId(@Param("agentId") String agentId);
}
