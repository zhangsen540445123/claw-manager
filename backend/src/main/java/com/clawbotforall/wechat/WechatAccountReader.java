package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 从 OpenClaw 状态文件读取已配对微信账号元数据。
 */
@Component
public class WechatAccountReader {

  private final ObjectMapper objectMapper;

  public WechatAccountReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * 从 OpenClaw 状态文件读取已配对微信账号。
   */

  public List<WechatPairedAccountEntity> readAccounts(
      InstanceEntity instance,
      InstancePaths paths,
      Map<String, String> remarksByAccountId
  ) {
    Path stateDir = paths.homeDir().resolve(".openclaw").resolve("openclaw-weixin");
    Path indexPath = stateDir.resolve("accounts.json");
    if (!Files.exists(indexPath)) {
      return List.of();
    }

    List<WechatPairedAccountEntity> accounts = new ArrayList<>();
    for (String accountId : accountIds(indexPath)) {
      Map<String, Object> payload = readJsonMap(stateDir.resolve("accounts").resolve(accountId + ".json"));
      WechatPairedAccountEntity account = new WechatPairedAccountEntity();
      account.setInstanceId(instance.getId());
      account.setAccountId(accountId);
      account.setWechatUserId(stringValue(payload.get("userId")));
      account.setRemark(remarksByAccountId.getOrDefault(accountId, ""));
      account.setBaseUrl(stringValue(payload.get("baseUrl")));
      account.setSavedAt(payload.get("savedAt") == null ? null : stringValue(payload.get("savedAt")));
      accounts.add(account);
    }
    return accounts;
  }

  public List<String> accountIds(Path indexPath) {
    try {
      List<Object> raw = objectMapper.readValue(indexPath.toFile(), new TypeReference<>() {});
      LinkedHashSet<String> ids = new LinkedHashSet<>();
      for (Object item : raw) {
        String value = stringValue(item).trim();
        if (!value.isBlank()) {
          ids.add(value);
        }
      }
      return List.copyOf(ids);
    } catch (IOException error) {
      return List.of();
    }
  }

  private Map<String, Object> readJsonMap(Path path) {
    if (!Files.exists(path)) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    } catch (IOException error) {
      return Map.of();
    }
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
