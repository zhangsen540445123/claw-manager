package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class WechatUserCleanupMigrationTest {
  @Test
  void protectedWechatAccountsIncludeActiveRebindAndCleanupOperations() throws IOException {
    String mapper = new ClassPathResource("mappers/wechat/WechatBindLinkMapper.xml")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(mapper).contains("FROM wechat_rebind_operations");
    assertThat(mapper).contains("old_account_id COLLATE utf8mb4_unicode_ci AS account_id");
    assertThat(mapper).contains("new_account_id COLLATE utf8mb4_unicode_ci AS account_id");
    assertThat(mapper).contains("account_id COLLATE utf8mb4_unicode_ci AS account_id");
    assertThat(mapper).contains("FROM wechat_user_cleanup_operations");
    assertThat(mapper).contains("status IN ('pending', 'cleaning', 'cleanup_failed')");
  }

  @Test
  void cleanupOperationsUseStableCollationAndRedactableIdentitySnapshot() throws IOException {
    String sql = new ClassPathResource("db/migration/V3__wechat_user_cleanup.sql").getContentAsString(StandardCharsets.UTF_8);
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS wechat_user_cleanup_operations");
    assertThat(sql).contains("DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    assertThat(sql).contains("active_subject_key");
    assertThat(sql).contains("cleanup_failed");
    assertThat(sql).contains("snapshot_json MEDIUMTEXT");
    assertThat(sql).contains("protected_agent_ids_json TEXT NULL");
    assertThat(sql).contains("MODIFY phone VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL");
    assertThat(sql).contains("MODIFY wechat_user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL");
    assertThat(sql).contains("MODIFY old_account_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL");
    assertThat(sql).contains("MODIFY new_account_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL");
    assertThat(sql).contains("MODIFY old_agent_id VARCHAR(37) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL");
    assertThat(sql).contains("MODIFY openviking_user_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL");
    String mapper = new ClassPathResource("mappers/wechat/WechatUserCleanupOperationMapper.xml")
        .getContentAsString(StandardCharsets.UTF_8);
    assertThat(mapper).contains("property=\"protectedAgentIdsJson\" column=\"protected_agent_ids_json\"");
    assertThat(mapper).contains("#{protectedAgentIdsJson}");
  }
}
