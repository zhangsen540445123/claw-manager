package com.clawbotforall.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 管理员账号持久化操作的 MyBatis Mapper。
 */
@Mapper
public interface AdminMapper {

  long countAll();

  AdminEntity findByEmail(@Param("email") String email);

  AdminEntity findById(@Param("id") String id);

  int insert(AdminEntity admin);

  int updateProfile(
      @Param("id") String id,
      @Param("name") String name,
      @Param("updatedAt") String updatedAt
  );

  int updatePassword(
      @Param("id") String id,
      @Param("passwordHash") String passwordHash,
      @Param("passwordSalt") String passwordSalt,
      @Param("mustChangePassword") boolean mustChangePassword,
      @Param("updatedAt") String updatedAt
  );
}
