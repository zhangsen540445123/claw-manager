package com.clawbotforall.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话持久化操作的 MyBatis Mapper。
 */
@Mapper
public interface SessionMapper {

  int insert(SessionEntity session);

  int deleteById(@Param("id") String id);

  int deleteExpired(@Param("now") String now);

  AdminEntity findAdminBySessionId(
      @Param("sessionId") String sessionId,
      @Param("now") String now
  );
}
