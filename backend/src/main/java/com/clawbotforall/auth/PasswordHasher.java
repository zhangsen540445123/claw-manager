package com.clawbotforall.auth;

/**
 * 用户密码哈希和校验的接口约定。
 */
public interface PasswordHasher {

  /**
   * 将明文密码哈希为可安全存储的哈希组成部分。
   */
  HashedPassword hash(String password);

  /**
   * 根据已存储盐值和哈希校验明文密码。
   */
  boolean verify(String password, String salt, String expectedHash);
}
