package com.clawbotforall.openviking;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpenVikingUserKeyMapper {

  OpenVikingUserKeyEntity find(
      @Param("accountId") String accountId,
      @Param("openvikingUserId") String openvikingUserId
  );

  int upsert(OpenVikingUserKeyEntity userKey);

  int delete(@Param("accountId") String accountId, @Param("openvikingUserId") String openvikingUserId);
}
