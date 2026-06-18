package com.clawbotforall.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WechatAccountReaderTest {

  @TempDir
  Path tempDir;

  @Test
  void readsAccountsJsonAndPreservesRemarks() throws Exception {
    Path homeDir = tempDir.resolve("home");
    Path stateDir = homeDir.resolve(".openclaw").resolve("openclaw-weixin");
    Path accountsDir = stateDir.resolve("accounts");
    Files.createDirectories(accountsDir);
    Files.writeString(stateDir.resolve("accounts.json"), "[\"wx_1\", \"wx_1\", \"wx_2\", \"\"]");
    Files.writeString(accountsDir.resolve("wx_1.json"), "{\"userId\":\"user-a\",\"baseUrl\":\"https://wx.example/a\",\"savedAt\":\"2026-06-15T00:00:00Z\"}");
    Files.writeString(accountsDir.resolve("wx_2.json"), "{\"userId\":\"user-b\"}");

    InstanceEntity instance = new InstanceEntity();
    instance.setId("inst_1");

    List<WechatPairedAccountEntity> accounts = new WechatAccountReader(new ObjectMapper()).readAccounts(
        instance,
        new InstancePaths(tempDir, homeDir, tempDir.resolve("workspace"), tempDir.resolve("logs")),
        Map.of("wx_1", "战神")
    );

    assertThat(accounts).extracting(WechatPairedAccountEntity::getAccountId)
        .containsExactly("wx_1", "wx_2");
    assertThat(accounts.getFirst().getRemark()).isEqualTo("战神");
    assertThat(accounts.getFirst().getWechatUserId()).isEqualTo("user-a");
    assertThat(accounts.getFirst().getBaseUrl()).isEqualTo("https://wx.example/a");
    assertThat(accounts.getFirst().getSavedAt()).isEqualTo("2026-06-15T00:00:00Z");
  }
}
