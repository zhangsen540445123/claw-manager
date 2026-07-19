package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.WechatAccountChannelEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同步和维护已绑定微信账号的实例状态文件与数据库映射。
 */
@Service
public class WechatAccountSyncService {

  private static final Logger log = LoggerFactory.getLogger(WechatAccountSyncService.class);
  private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceFileService fileService;
  private final WechatAccountReader accountReader;
  private final WechatBindLinkMapper bindLinkMapper;
  private final ObjectMapper objectMapper;

  public WechatAccountSyncService(
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      InstanceFileService fileService,
      WechatAccountReader accountReader,
      WechatBindLinkMapper bindLinkMapper,
      ObjectMapper objectMapper
  ) {
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.fileService = fileService;
    this.accountReader = accountReader;
    this.bindLinkMapper = bindLinkMapper;
    this.objectMapper = objectMapper;
  }

  /**
   * 读取实例目录中的账号文件，并刷新已落库账号的展示元数据。
   */
  @Transactional
  public List<WechatPairedAccountEntity> syncInstanceAccounts(InstanceEntity instance) {
    List<WechatPairedAccountEntity> existing = aggregateMapper.listWechatAccountsByInstanceIds(List.of(instance.getId()));
    Map<String, WechatPairedAccountEntity> existingByAccountId = existing.stream()
        .collect(Collectors.toMap(WechatPairedAccountEntity::getAccountId, item -> item, (left, right) -> left, LinkedHashMap::new));
    Map<String, String> remarks = new LinkedHashMap<>();
    for (WechatPairedAccountEntity account : existing) {
      remarks.putIfAbsent(account.getAccountId(), account.getRemark());
    }

    List<WechatPairedAccountEntity> rawAccounts = readRawAccounts(instance, remarks);
    String now = Instant.now().toString();
    for (WechatPairedAccountEntity raw : rawAccounts) {
      WechatPairedAccountEntity existingAccount = existingByAccountId.get(raw.getAccountId());
      if (existingAccount == null) {
        continue;
      }
      String rawWechatUserId = defaultString(raw.getWechatUserId()).trim();
      String persistedWechatUserId = defaultString(existingAccount.getWechatUserId()).trim();
      if (persistedWechatUserId.isBlank() && !rawWechatUserId.isBlank()) {
        existingAccount.setWechatUserId(rawWechatUserId);
      }
      existingAccount.setBaseUrl(raw.getBaseUrl());
      existingAccount.setSavedAt(raw.getSavedAt());
      existingAccount.setUpdatedAt(now);
      mutationMapper.updateWechatAccountMetadata(existingAccount);
    }

    List<WechatPairedAccountEntity> latest = aggregateMapper.listWechatAccountsByInstanceIds(List.of(instance.getId()));
    ensureAccountChannels(latest, now);
    return latest;
  }

  /**
   * 同步多个实例的已绑定微信账号展示元数据。
   */
  @Transactional
  public void syncInstances(List<InstanceEntity> instances) {
    for (InstanceEntity instance : instances) {
      syncInstanceAccounts(instance);
    }
  }

  /**
   * 直接读取实例状态目录中的账号，不写入数据库。
   */
  public List<WechatPairedAccountEntity> readRawAccounts(InstanceEntity instance, Map<String, String> remarksByAccountId) {
    InstancePaths paths = fileService.paths(instance.getId());
    return accountReader.readAccounts(instance, paths, remarksByAccountId == null ? Map.of() : remarksByAccountId);
  }

  /**
   * 读取实例状态目录中 accounts.json 记录的微信唯一标识。
   */
  public List<String> readRawAccountIds(InstanceEntity instance) {
    Path stateDir = fileService.paths(instance.getId()).homeDir().resolve(".openclaw").resolve("openclaw-weixin");
    Path indexPath = stateDir.resolve("accounts.json");
    if (!Files.exists(indexPath)) {
      return List.of();
    }
    return accountReader.accountIds(indexPath);
  }

  /**
   * 更新已绑定微信账号的本地备注。
   */
  @Transactional
  public List<WechatPairedAccountEntity> updateRemark(
      InstanceEntity instance,
      String accountId,
      String remark
  ) {
    WechatPairedAccountEntity account = aggregateMapper.findWechatAccountByAccountId(accountId);
    if (account == null || !instance.getId().equals(account.getInstanceId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "微信绑定账号不存在。");
    }
    mutationMapper.updateWechatAccountRemark(instance.getId(), accountId, remark, Instant.now().toString());
    return syncInstanceAccounts(instance);
  }

  @Transactional
  public List<WechatPairedAccountEntity> updateProfile(
      InstanceEntity instance,
      String accountId,
      String phone,
      String remark
  ) {
    WechatPairedAccountEntity account = aggregateMapper.findWechatAccountByAccountId(accountId);
    if (account == null || !instance.getId().equals(account.getInstanceId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "微信绑定账号不存在。");
    }
    String normalizedPhone = normalizeOptionalPhone(phone);
    if (normalizedPhone != null) {
      WechatPairedAccountEntity duplicate = aggregateMapper.findWechatAccountByPhone(normalizedPhone);
      if (duplicate != null && !defaultString(duplicate.getAccountId()).equals(accountId)) {
        throw new ApiException(HttpStatus.CONFLICT, "该手机号已绑定到其他微信用户。");
      }
    }
    mutationMapper.updateWechatAccountProfile(
        instance.getId(),
        accountId,
        normalizedPhone,
        sanitizeRemark(remark),
        Instant.now().toString()
    );
    return syncInstanceAccounts(instance);
  }

  /**
   * 删除一个已绑定账号及其状态文件。
   */
  @Transactional
  public List<WechatPairedAccountEntity> deleteAccount(InstanceEntity instance, String accountId) {
    WechatPairedAccountEntity account = aggregateMapper.findWechatAccountByAccountId(accountId);
    if (account == null || !instance.getId().equals(account.getInstanceId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "微信绑定账号不存在。");
    }
    removeAccountStateFiles(fileService.paths(instance.getId()), accountId);
    mutationMapper.deleteWechatAccount(instance.getId(), accountId);
    bindLinkMapper.deleteByPhoneOrAccountId(account.getPhone(), accountId);
    List<WechatPairedAccountEntity> latest = syncInstanceAccounts(instance);
    log.info("已删除微信绑定账号：instanceId={}, remainingCount={}", instance.getId(), latest.size());
    return latest;
  }

  /**
   * 删除实例下全部绑定账号及其状态文件。
   */
  @Transactional
  public boolean deleteAllAccounts(InstanceEntity instance) {
    boolean hadAccounts = !aggregateMapper.listWechatAccountsByInstanceIds(List.of(instance.getId())).isEmpty();
    removeStateDir(fileService.paths(instance.getId()));
    mutationMapper.deleteWechatAccountsForInstance(instance.getId());
    bindLinkMapper.deleteByInstanceId(instance.getId());
    log.info("已删除实例全部微信绑定账号：instanceId={}, hadAccounts={}", instance.getId(), hadAccounts);
    return hadAccounts;
  }

  /**
   * 删除实例状态目录里的单个微信账号文件，并从 accounts.json 中移除索引。
   */
  public void removeAccountStateFiles(InstancePaths paths, String accountId) {
    Path stateDir = weixinStateDir(paths);
    Path accountsDir = stateDir.resolve("accounts");
    for (String suffix : List.of(".json", ".sync.json", ".context-tokens.json")) {
      try {
        Files.deleteIfExists(accountsDir.resolve(accountId + suffix));
      } catch (IOException ignored) {
        // 尽力处理；下次同步会反映剩余状态。
      }
    }
    rewriteAccountIndex(stateDir.resolve("accounts.json"), accountId);
  }

  public boolean refreshAccountCredentialsFromRejectedLogin(
      InstanceEntity sourceInstance,
      String sourceAccountId,
      WechatPairedAccountEntity targetAccount
  ) {
    String normalizedSourceAccountId = defaultString(sourceAccountId).trim();
    String targetAccountId = targetAccount == null ? "" : defaultString(targetAccount.getAccountId()).trim();
    String targetInstanceId = targetAccount == null ? "" : defaultString(targetAccount.getInstanceId()).trim();
    if (sourceInstance == null || normalizedSourceAccountId.isBlank() || targetAccountId.isBlank() || targetInstanceId.isBlank()) {
      return false;
    }
    InstancePaths sourcePaths = fileService.paths(sourceInstance.getId());
    InstancePaths targetPaths = fileService.paths(targetInstanceId);
    Path sourceCredential = accountStateFile(sourcePaths, normalizedSourceAccountId, ".json");
    if (!Files.exists(sourceCredential)) {
      return false;
    }
    Path targetCredential = accountStateFile(targetPaths, targetAccountId, ".json");
    try {
      Files.createDirectories(targetCredential.getParent());
      if (!sourceCredential.toAbsolutePath().normalize().equals(targetCredential.toAbsolutePath().normalize())) {
        Files.copy(sourceCredential, targetCredential, StandardCopyOption.REPLACE_EXISTING);
      }
      Files.deleteIfExists(accountStateFile(targetPaths, targetAccountId, ".sync.json"));
      Files.deleteIfExists(accountStateFile(targetPaths, targetAccountId, ".context-tokens.json"));
      ensureAccountIndexed(weixinStateDir(targetPaths).resolve("accounts.json"), targetAccountId);
      return true;
    } catch (IOException error) {
      log.warn(
          "刷新重复微信原账号凭证失败：sourceInstanceId={}, sourceAccountHash={}, targetInstanceId={}, targetAccountHash={}, reason={}",
          sourceInstance.getId(),
          WechatLogSanitizer.identityHashPreview(normalizedSourceAccountId),
          targetInstanceId,
          WechatLogSanitizer.identityHashPreview(targetAccountId),
          error.getMessage()
      );
      return false;
    }
  }

  private void ensureAccountChannels(List<WechatPairedAccountEntity> accounts, String now) {
    for (WechatPairedAccountEntity account : accounts) {
      if (defaultString(account.getWechatUserId()).trim().isBlank()) {
        continue;
      }
      WechatAccountChannelEntity channel = new WechatAccountChannelEntity();
      channel.setAccountId(account.getAccountId());
      channel.setInstanceId(account.getInstanceId());
      channel.setWechatUserId(account.getWechatUserId());
      channel.setStatus("unknown");
      channel.setMessage("");
      channel.setOutputSnippet("");
      channel.setLastStartedAt(null);
      channel.setLastErrorAt(null);
      channel.setUpdatedAt(now);
      mutationMapper.ensureWechatAccountChannel(channel);
    }
  }

  private void removeStateDir(InstancePaths paths) {
    Path stateDir = weixinStateDir(paths);
    if (!Files.exists(stateDir)) {
      return;
    }
    try (var walk = Files.walk(stateDir)) {
      walk.sorted((left, right) -> right.compareTo(left))
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException ignored) {
              // 尽力处理；下次同步会反映剩余状态。
            }
          });
    } catch (IOException ignored) {
      // 尽力处理。
    }
  }

  private Path accountStateFile(InstancePaths paths, String accountId, String suffix) {
    return weixinStateDir(paths).resolve("accounts").resolve(accountId + suffix);
  }

  private Path weixinStateDir(InstancePaths paths) {
    return paths.homeDir().resolve(".openclaw").resolve("openclaw-weixin");
  }

  private void ensureAccountIndexed(Path indexPath, String accountId) throws IOException {
    LinkedHashSet<String> accountIds = new LinkedHashSet<>();
    if (Files.exists(indexPath)) {
      try {
        List<Object> raw = objectMapper.readValue(indexPath.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        for (Object item : raw) {
          String value = defaultString(item == null ? null : String.valueOf(item)).trim();
          if (!value.isBlank()) {
            accountIds.add(value);
          }
        }
      } catch (IOException ignored) {
        // Rewrite malformed index files with the known target account below.
      }
    }
    accountIds.add(accountId);
    Files.createDirectories(indexPath.getParent());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), new ArrayList<>(accountIds));
  }

  private void rewriteAccountIndex(Path indexPath, String removedAccountId) {
    if (!Files.exists(indexPath)) {
      return;
    }
    try {
      List<Object> raw = objectMapper.readValue(indexPath.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
      LinkedHashSet<String> accountIds = new LinkedHashSet<>();
      for (Object item : raw) {
        String value = defaultString(item == null ? null : String.valueOf(item)).trim();
        if (!value.isBlank() && !value.equals(removedAccountId)) {
          accountIds.add(value);
        }
      }
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), new ArrayList<>(accountIds));
    } catch (IOException ignored) {
      // 尽力处理。
    }
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  private static String normalizeOptionalPhone(String phone) {
    String normalized = defaultString(phone).replaceAll("\\s+", "");
    if (normalized.isBlank()) {
      return null;
    }
    if (!PHONE_PATTERN.matcher(normalized).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "手机号格式无效。");
    }
    return normalized;
  }

  private static String sanitizeRemark(String remark) {
    String normalized = defaultString(remark).trim().replaceAll("\\s+", " ");
    return normalized.substring(0, Math.min(60, normalized.length()));
  }
}
