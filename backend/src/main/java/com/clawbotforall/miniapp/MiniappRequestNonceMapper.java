package com.clawbotforall.miniapp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MiniappRequestNonceMapper {
  int insert(
      @Param("appId") String appId,
      @Param("nonce") String nonce,
      @Param("createdAt") String createdAt,
      @Param("expiresAt") String expiresAt
  );

  int deleteExpired(@Param("now") String now);
}
