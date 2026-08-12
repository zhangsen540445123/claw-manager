package com.clawbotforall.useragent;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserAgentIdentityMapper {
  List<UserAgentIdentityEntity> listAll();

  UserAgentIdentityEntity findByWechatUserId(@Param("wechatUserId") String wechatUserId);

  UserAgentIdentityEntity findByWechatUserIdForUpdate(@Param("wechatUserId") String wechatUserId);

  int insert(UserAgentIdentityEntity identity);

  int deleteByAgentId(@Param("agentId") String agentId);
}
