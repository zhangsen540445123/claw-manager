package com.clawbotforall.miniapp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MiniappClientMapper {
  MiniappClientEntity findByAppId(@Param("appId") String appId);
}
