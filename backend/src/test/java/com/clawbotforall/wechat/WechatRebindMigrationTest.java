package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class WechatRebindMigrationTest {

  @Test
  void rebindOperationAuditUsesStableCollationWithoutDestructiveStringForeignKeys() throws IOException {
    String sql = new ClassPathResource("db/migration/V2__wechat_rebind_cleanup.sql")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql).contains("DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    assertThat(sql).contains("uk_wechat_rebind_operations_active_phone");
    assertThat(sql).contains("uk_wechat_rebind_operations_active_wechat_user");
    assertThat(sql).contains("'cancelled'");
    assertThat(sql).doesNotContain("FOREIGN KEY (bind_token)");
    assertThat(sql).doesNotContain("FOREIGN KEY (old_instance_id)");
    assertThat(sql).doesNotContain("FOREIGN KEY (new_instance_id)");
    assertThat(sql).doesNotContain("ON DELETE CASCADE");
  }

  @Test
  void cleanupFailedScannedAccountsRemainProtectedForRetry() throws IOException {
    String mapper = new ClassPathResource("mappers/wechat/WechatBindLinkMapper.xml")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(mapper).contains("'waiting_scan', 'scanned', 'initializing', 'cleaning', 'cleanup_failed'");
  }
}
