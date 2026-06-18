package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceWechatBindingEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.InstancePaths;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同步和维护已绑定微信账号的实例状态文件与数据库映射。
 */
@Service
public class WechatAccountSyncService {

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
    Map<String, String> remarks = existing.stream()
        .collect(Collectors.toMap(WechatPairedAccountEntity::getAccountId, WechatPairedAccountEntity::getRemark, (left, right) -> left));

    List<WechatPairedAccountEntity> rawAccounts = readRawAccounts(instance, remarks);
    String now = Instant.now().toString();
    for (WechatPairedAccountEntity raw : rawAccounts) {
      WechatPairedAccountEntity existingAccount = existingByAccountId.get(raw.getAccountId());
      if (existingAccount == null) {
        continue;
      }
      existingAccount.setWechatUserId(raw.getWechatUserId());
      existingAccount.setBaseUrl(raw.getBaseUrl());
      existingAccount.setSavedAt(raw.getSavedAt());
      existingAccount.setUpdatedAt(now);
      mutationMapper.updateWechatAccountMetadata(existingAccount);
    }

    List<WechatPairedAccountEntity> latest = aggregateMapper.listWechatAccountsByInstanceIds(List.of(instance.getId()));
    patchBindingFromAccounts(instance, latest);
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
    if (latest.isEmpty()) {
      mutationMapper.updateWechatBinding(idleBinding(instance.getId(), "当前微信绑定已解除，可重新生成二维码。"));
    }
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
    InstanceWechatBindingEntity binding = idleBinding(instance.getId(), "当前微信绑定已解除，可重新生成二维码。");
    mutationMapper.updateWechatBinding(binding);
    return hadAccounts;
  }

  /**
   * 删除实例状态目录里的单个微信账号文件，并从 accounts.json 中移除索引。
   */
  public void removeAccountStateFiles(InstancePaths paths, String accountId) {
    Path stateDir = paths.homeDir().resolve(".openclaw").resolve("openclaw-weixin");
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

  private void patchBindingFromAccounts(
      InstanceEntity instance,
      List<WechatPairedAccountEntity> accounts
  ) {
    List<InstanceWechatBindingEntity> bindings = aggregateMapper.listWechatBindingByInstanceIds(List.of(instance.getId()));
    InstanceWechatBindingEntity current = bindings.isEmpty() ? null : bindings.getFirst();
    InstanceWechatBindingEntity next = new InstanceWechatBindingEntity();
    next.setInstanceId(instance.getId());
    next.setStatus(accounts.isEmpty() ? defaultString(current == null ? "idle" : current.getStatus()) : "connected");
    next.setQrMode(accounts.isEmpty() && current != null ? current.getQrMode() : null);
    next.setQrPayload(accounts.isEmpty() && current != null ? defaultString(current.getQrPayload()) : "");
    next.setQrLink(accounts.isEmpty() && current != null ? defaultString(current.getQrLink()) : "");
    next.setQrExpiresAt(accounts.isEmpty() && current != null ? current.getQrExpiresAt() : null);
    next.setOutputSnippet(accounts.isEmpty() && current != null ? defaultString(current.getOutputSnippet()) : "");
    next.setRuntimeReady(!accounts.isEmpty());
    next.setRuntimeStatus(accounts.isEmpty() ? runtimeStatus(current) : "ready");
    next.setRuntimeMessage("");
    next.setRuntimeUpdatedAt(accounts.isEmpty() ? (current == null ? null : current.getRuntimeUpdatedAt()) : Instant.now().toString());
    next.setUpdatedAt(accounts.isEmpty() ? (current == null ? null : current.getUpdatedAt()) : Instant.now().toString());
    mutationMapper.updateWechatBinding(next);
  }

  private InstanceWechatBindingEntity idleBinding(String instanceId, String message) {
    InstanceWechatBindingEntity binding = new InstanceWechatBindingEntity();
    binding.setInstanceId(instanceId);
    binding.setStatus("idle");
    binding.setQrMode(null);
    binding.setQrPayload("");
    binding.setQrLink("");
    binding.setQrExpiresAt(null);
    binding.setOutputSnippet(message);
    binding.setRuntimeReady(false);
    binding.setRuntimeStatus("idle");
    binding.setRuntimeMessage("");
    binding.setRuntimeUpdatedAt(null);
    binding.setUpdatedAt(Instant.now().toString());
    return binding;
  }

  private void removeStateDir(InstancePaths paths) {
    Path stateDir = paths.homeDir().resolve(".openclaw").resolve("openclaw-weixin");
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

  private String runtimeStatus(InstanceWechatBindingEntity current) {
    if (current == null || defaultString(current.getRuntimeStatus()).isBlank()) {
      return "idle";
    }
    return current.getRuntimeStatus();
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
