package com.clawbotforall.auth;

/**
 * 密码哈希器生成的结构化密码哈希组成部分。
 */
public record HashedPassword(
    String salt,
    String hash
) {}
