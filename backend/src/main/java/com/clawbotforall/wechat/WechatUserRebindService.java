package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.WechatAccountChannelEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.miniapp.MiniappUserBindingEntity;
import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.miniapp.MiniappUserKeyMapper;
import com.clawbotforall.openviking.OpenVikingUserKeyService;
import com.clawbotforall.useragent.UserAgentIdentityEntity;
import com.clawbotforall.useragent.UserAgentIdentityMapper;
import com.clawbotforall.useragent.UserAgentIdentityResult;
import com.clawbotforall.useragent.UserAgentIdentityService;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 老微信用户重新扫码后的可恢复清理与身份替换编排。
 *
 * <p>数据库只记录最后成功阶段；外部 RPC 和文件操作均设计为幂等，因此服务重启后可以从该阶段继续。</p>
 */
@Service
public class WechatUserRebindService {
  private static final Logger log = LoggerFactory.getLogger(WechatUserRebindService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final List<String> STAGES = List.of(
      "validated",
      "channels_stopped",
      "miniapp_deleted",
      "identity_replaced",
      "routing_replaced",
      "local_files_deleted",
      "wechat_account_migrated",
      "openviking_key_rotated",
      "gateway_restarted",
      "completed"
  );

  private final WechatRebindOperationMapper operationMapper;
  private final WechatBindLinkMapper linkMapper;
  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final UserAgentIdentityMapper identityMapper;
  private final UserAgentIdentityService identityService;
  private final MiniappUserBindingMapper miniappBindingMapper;
  private final MiniappUserKeyMapper miniappKeyMapper;
  private final OpenClawGatewayRpcService gatewayRpcService;
  private final OpenClawUserDataCleaner dataCleaner;
  private final WechatAccountSyncService accountSyncService;
  private final InstanceFileService fileService;
  private final OpenVikingUserKeyService userKeyService;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;

  public WechatUserRebindService(
      WechatRebindOperationMapper operationMapper,
      WechatBindLinkMapper linkMapper,
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      UserAgentIdentityMapper identityMapper,
      UserAgentIdentityService identityService,
      MiniappUserBindingMapper miniappBindingMapper,
      MiniappUserKeyMapper miniappKeyMapper,
      OpenClawGatewayRpcService gatewayRpcService,
      OpenClawUserDataCleaner dataCleaner,
      WechatAccountSyncService accountSyncService,
      InstanceFileService fileService,
      OpenVikingUserKeyService userKeyService,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager
  ) {
    this.operationMapper = operationMapper;
    this.linkMapper = linkMapper;
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.identityMapper = identityMapper;
    this.identityService = identityService;
    this.miniappBindingMapper = miniappBindingMapper;
    this.miniappKeyMapper = miniappKeyMapper;
    this.gatewayRpcService = gatewayRpcService;
    this.dataCleaner = dataCleaner;
    this.accountSyncService = accountSyncService;
    this.fileService = fileService;
    this.userKeyService = userKeyService;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public WechatBindLinkEntity startOrResume(
      WechatBindLinkEntity link,
      WechatPairedAccountEntity oldAccount,
      InstanceEntity newInstance,
      String newAccountId,
      String scannedWechatUserId
  ) {
    requireRebindInput(link, oldAccount, newInstance, newAccountId, scannedWechatUserId);
    WechatRebindOperationEntity operation;
    try {
      operation = transactions.execute(status ->
          captureOrLoad(link, oldAccount, newInstance, newAccountId.trim(), scannedWechatUserId.trim()));
    } catch (DuplicateKeyException error) {
      throw new ApiException(HttpStatus.CONFLICT, "该用户已有进行中的重新绑定清理任务。");
    }
    return execute(operation, newInstance);
  }

  public WechatBindLinkEntity retry(String bindToken) {
    String normalizedToken = text(bindToken);
    if (normalizedToken.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "绑定 token 不能为空。");
    }
    WechatRebindOperationEntity operation = transactions.execute(status -> {
      WechatRebindOperationEntity locked = operationMapper.findByTokenForUpdate(normalizedToken);
      WechatBindLinkEntity link = linkMapper.findByTokenForUpdate(normalizedToken);
      if (locked == null || link == null) {
        throw new ApiException(HttpStatus.NOT_FOUND, "重新绑定清理任务不存在。");
      }
      if (!"cleanup_failed".equals(locked.getStatus()) || !"cleanup_failed".equals(link.getStatus())) {
        throw new ApiException(HttpStatus.CONFLICT, "仅失败的清理任务可以重试。");
      }
      locked.setStatus("cleaning");
      locked.setLastError(null);
      locked.setUpdatedAt(now());
      operationMapper.update(locked);
      link.setStatus("cleaning");
      link.setCleanupError(null);
      link.setErrorMessage(null);
      link.setUpdatedAt(now());
      linkMapper.update(link);
      return locked;
    });
    InstanceEntity instance = aggregateMapper.findById(operation.getNewInstanceId());
    if (instance == null) {
      return fail(operation, "目标 OpenClaw 实例不存在。");
    }
    return execute(operation, instance);
  }

  /**
   * 取消仍处于可逆阶段的失败清理任务，并恢复原微信账号通道。
   *
   * <p>身份替换之后旧 Agent 已经被删除，继续回滚会造成身份和路由不一致，因此只允许在
   * {@code identity_replaced} 之前取消。数据库行锁会覆盖校验、临时文件清理和旧通道恢复，
   * 避免管理员同时触发重试和取消。</p>
   */
  public WechatBindLinkEntity cancelFailed(String bindToken) {
    String normalizedToken = text(bindToken);
    if (normalizedToken.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "绑定 token 不能为空。");
    }
    return transactions.execute(status -> {
      WechatRebindOperationEntity operation = operationMapper.findByTokenForUpdate(normalizedToken);
      WechatBindLinkEntity link = linkMapper.findByTokenForUpdate(normalizedToken);
      if (operation == null || link == null) {
        throw new ApiException(HttpStatus.NOT_FOUND, "重新绑定清理任务不存在。");
      }
      if (!"cleanup_failed".equals(operation.getStatus()) || !"cleanup_failed".equals(link.getStatus())) {
        throw new ApiException(HttpStatus.CONFLICT, "仅失败的清理任务可以取消。");
      }
      if (stageIndex(operation.getStage()) >= stageIndex("identity_replaced")) {
        throw new ApiException(HttpStatus.CONFLICT, "重新绑定已进入不可逆阶段，请继续重试清理。");
      }

      InstanceEntity instance = aggregateMapper.findById(operation.getOldInstanceId());
      if (instance == null) {
        throw new ApiException(HttpStatus.CONFLICT, "原 OpenClaw 实例不存在，无法恢复旧微信通道。");
      }
      WechatPairedAccountEntity persistedNewAccount = aggregateMapper.findWechatAccountByAccountId(
          operation.getNewAccountId());
      String oldAccountId = text(operation.getOldAccountId());
      String newAccountId = text(operation.getNewAccountId());
      if (!Objects.equals(newAccountId, oldAccountId)
          && persistedNewAccount == null
          && !newAccountId.isBlank()) {
        accountSyncService.removeAccountStateFiles(
            fileService.paths(operation.getNewInstanceId()), newAccountId);
      }
      gatewayRpcService.startWechatChannel(instance, List.of(operation.getOldAccountId()));

      String timestamp = now();
      operation.setStatus("cancelled");
      operation.setLastError(null);
      operation.setCompletedAt(timestamp);
      operation.setUpdatedAt(timestamp);
      operationMapper.update(operation);

      link.setStatus("revoked");
      link.setCleanupStage(operation.getStage());
      link.setCleanupError(null);
      link.setErrorMessage("重新绑定清理已由管理员取消。");
      redactTerminalAudit(link);
      link.setCompletedAt(timestamp);
      link.setUpdatedAt(timestamp);
      linkMapper.update(link);
      return link;
    });
  }

  private WechatRebindOperationEntity captureOrLoad(
      WechatBindLinkEntity suppliedLink,
      WechatPairedAccountEntity oldAccount,
      InstanceEntity newInstance,
      String newAccountId,
      String scannedWechatUserId
  ) {
    WechatBindLinkEntity link = linkMapper.findByTokenForUpdate(suppliedLink.getToken());
    if (link == null) {
      link = suppliedLink;
    }
    WechatRebindOperationEntity existing = operationMapper.findByTokenForUpdate(link.getToken());
    if (existing != null) {
      return existing;
    }
    WechatPairedAccountEntity lockedOldAccount = aggregateMapper.findWechatAccountByAccountIdForUpdate(oldAccount.getAccountId());
    validateLockedOldAccount(link, oldAccount, lockedOldAccount, scannedWechatUserId);

    UserAgentIdentityEntity identity = identityMapper.findByWechatUserIdForUpdate(scannedWechatUserId);
    if (identity == null) {
      throw new ApiException(HttpStatus.CONFLICT, "旧用户 Agent 身份不存在，无法重新绑定。");
    }
    WechatRebindOperationEntity active = operationMapper.findActiveForUserForUpdate(link.getPhone(), scannedWechatUserId);
    if (active != null && !Objects.equals(active.getBindToken(), link.getToken())) {
      throw new ApiException(HttpStatus.CONFLICT, "该用户已有进行中的重新绑定清理任务。");
    }
    List<String> apiPeers = miniappBindingMapper.listByAgentId(identity.getAgentId()).stream()
        .map(MiniappUserBindingEntity::getOpenidHash)
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(value -> "api:" + value)
        .distinct()
        .toList();
    List<String> oldSessions = dataCleaner.readOldSessionIds(lockedOldAccount.getInstanceId(), identity.getAgentId());
    String timestamp = now();
    WechatRebindOperationEntity operation = new WechatRebindOperationEntity();
    operation.setBindToken(link.getToken());
    operation.setPhone(link.getPhone());
    operation.setWechatUserId(scannedWechatUserId);
    operation.setOldInstanceId(lockedOldAccount.getInstanceId());
    operation.setOldAccountId(lockedOldAccount.getAccountId());
    operation.setNewInstanceId(newInstance.getId());
    operation.setNewAccountId(newAccountId);
    operation.setOldAgentId(identity.getAgentId());
    operation.setNewAgentId(generateAgentId());
    operation.setOpenvikingUserId(identity.getOpenvikingUserId());
    operation.setApiPeerIdsJson(writeJson(apiPeers));
    operation.setOldSessionIdsJson(writeJson(oldSessions));
    operation.setAccountSnapshotJson(writeJson(lockedOldAccount));
    operation.setStatus("cleaning");
    operation.setStage("validated");
    operation.setAttemptCount(0);
    operation.setCreatedAt(timestamp);
    operation.setUpdatedAt(timestamp);
    operationMapper.insert(operation);

    link.setStatus("cleaning");
    // The old account is preserved in the operation snapshot. Protect the scanned account from
    // the periodic ghost-account cleanup while the rebind task is still in progress.
    link.setTargetAccountId(newAccountId);
    link.setCleanupStage("validated");
    link.setCleanupError(null);
    link.setErrorMessage(null);
    link.setScannedWechatUserId(scannedWechatUserId);
    link.setUpdatedAt(timestamp);
    linkMapper.update(link);
    return operation;
  }

  private WechatBindLinkEntity execute(WechatRebindOperationEntity operation, InstanceEntity instance) {
    incrementAttempt(operation);
    try {
      if (!sameInstance(operation)) {
        throw new IllegalStateException("暂不支持跨实例重新绑定，请在原实例完成重新扫码。");
      }
      if (before(operation, "channels_stopped")) {
        gatewayRpcService.stopWechatChannel(instance, accountIds(operation));
        advance(operation, "channels_stopped");
      }
      if (before(operation, "miniapp_deleted")) {
        miniappKeyMapper.deleteByAgentId(operation.getOldAgentId());
        miniappBindingMapper.deleteByAgentId(operation.getOldAgentId());
        advance(operation, "miniapp_deleted");
      }
      if (before(operation, "identity_replaced")) {
        UserAgentIdentityResult replacement = identityService.replaceForRebind(
            operation.getOldInstanceId(), operation.getWechatUserId(), operation.getOldAgentId(), operation.getNewAgentId());
        operation.setNewAgentId(replacement.agentId());
        operation.setOpenvikingUserId(replacement.openVikingUserId());
        advance(operation, "identity_replaced");
      }
      if (before(operation, "routing_replaced")) {
        OpenClawGatewayRpcService.ReplaceUserAgentResult result = gatewayRpcService.replaceUserAgent(
            instance,
            operation.getNewAgentId(),
            operation.getOpenvikingUserId(),
            operation.getNewAccountId(),
            operation.getWechatUserId(),
            operation.getOldAgentId(),
            apiPeers(operation)
        );
        if (!result.conflictingBindings().isEmpty()) {
          throw new IllegalStateException("OpenClaw 路由冲突，旧 Agent 仍被其他 binding 引用。");
        }
        if (!result.success()) {
          throw new IllegalStateException("OpenClaw 用户路由替换未完整持久化或应用到运行时。");
        }
        advance(operation, "routing_replaced");
      }
      if (before(operation, "local_files_deleted")) {
        dataCleaner.deleteOldUserData(
            operation.getOldInstanceId(), operation.getOldAgentId(), oldSessionIds(operation), apiPeers(operation));
        advance(operation, "local_files_deleted");
      }
      if (before(operation, "wechat_account_migrated")) {
        transactions.executeWithoutResult(status -> migrateWechatAccountRecords(operation));
        if (!Objects.equals(operation.getOldAccountId(), operation.getNewAccountId())) {
          accountSyncService.removeAccountStateFiles(
              fileService.paths(operation.getOldInstanceId()), operation.getOldAccountId());
        }
        advance(operation, "wechat_account_migrated");
      }
      if (before(operation, "openviking_key_rotated")) {
        userKeyService.rotateUserKey(operation.getOpenvikingUserId());
        advance(operation, "openviking_key_rotated");
      }
      if (before(operation, "gateway_restarted")) {
        gatewayRpcService.startWechatChannel(instance, List.of(operation.getNewAccountId()));
        accountSyncService.syncInstanceAccounts(instance);
        advance(operation, "gateway_restarted");
      }
      complete(operation);
    } catch (RuntimeException error) {
      log.warn("微信用户重新绑定清理失败：tokenHash={}, stage={}, reason={}",
          Integer.toHexString(operation.getBindToken().hashCode()), operation.getStage(), sanitize(error.getMessage()));
      return fail(operation, error.getMessage());
    }
    return linkMapper.findByToken(operation.getBindToken());
  }

  private void migrateWechatAccountRecords(WechatRebindOperationEntity operation) {
    WechatPairedAccountEntity snapshot = readJson(operation.getAccountSnapshotJson(), WechatPairedAccountEntity.class);
    WechatPairedAccountEntity existingNew = aggregateMapper.findWechatAccountByAccountId(operation.getNewAccountId());
    if (existingNew != null
        && (!Objects.equals(existingNew.getInstanceId(), operation.getNewInstanceId())
            || !Objects.equals(text(existingNew.getWechatUserId()), text(operation.getWechatUserId()))
            || !Objects.equals(text(existingNew.getPhone()), text(operation.getPhone())))) {
      throw new IllegalStateException("新微信 accountId 已被其他用户或实例占用。");
    }

    if (!Objects.equals(operation.getOldAccountId(), operation.getNewAccountId())) {
      mutationMapper.deleteWechatAccount(operation.getOldInstanceId(), operation.getOldAccountId());
    }
    if (existingNew == null) {
      String timestamp = now();
      snapshot.setAccountId(operation.getNewAccountId());
      snapshot.setInstanceId(operation.getNewInstanceId());
      snapshot.setPhone(operation.getPhone());
      snapshot.setWechatUserId(operation.getWechatUserId());
      snapshot.setSavedAt(timestamp);
      snapshot.setUpdatedAt(timestamp);
      mutationMapper.insertWechatAccount(snapshot);
    }
    ensureChannel(operation);
  }

  private void ensureChannel(WechatRebindOperationEntity operation) {
    WechatAccountChannelEntity channel = new WechatAccountChannelEntity();
    channel.setAccountId(operation.getNewAccountId());
    channel.setInstanceId(operation.getNewInstanceId());
    channel.setWechatUserId(operation.getWechatUserId());
    channel.setStatus("starting");
    channel.setMessage("老用户重新绑定完成，正在启动微信通道。");
    channel.setUpdatedAt(now());
    mutationMapper.ensureWechatAccountChannel(channel);
  }

  private void incrementAttempt(WechatRebindOperationEntity operation) {
    transactions.executeWithoutResult(status -> {
      operation.setAttemptCount(operation.getAttemptCount() + 1);
      operation.setStatus("cleaning");
      operation.setLastError(null);
      operation.setUpdatedAt(now());
      operationMapper.update(operation);
    });
  }

  private void advance(WechatRebindOperationEntity operation, String stage) {
    transactions.executeWithoutResult(status -> {
      operation.setStage(stage);
      operation.setStatus("cleaning");
      operation.setUpdatedAt(now());
      operationMapper.update(operation);
      WechatBindLinkEntity link = requireLink(operation.getBindToken());
      link.setStatus("cleaning");
      link.setCleanupStage(stage);
      link.setCleanupError(null);
      link.setErrorMessage(null);
      link.setUpdatedAt(now());
      linkMapper.update(link);
    });
  }

  private void complete(WechatRebindOperationEntity operation) {
    if ("completed".equals(operation.getStage()) && "completed".equals(operation.getStatus())) {
      return;
    }
    transactions.executeWithoutResult(status -> {
      String timestamp = now();
      operation.setStage("completed");
      operation.setStatus("completed");
      operation.setLastError(null);
      operation.setCompletedAt(timestamp);
      operation.setUpdatedAt(timestamp);
      operationMapper.update(operation);

      WechatBindLinkEntity link = requireLink(operation.getBindToken());
      link.setStatus("connected");
      link.setCleanupStage("completed");
      link.setCleanupError(null);
      link.setErrorMessage(null);
      redactTerminalAudit(link);
      link.setCompletedAt(timestamp);
      link.setUpdatedAt(timestamp);
      linkMapper.update(link);
    });
  }

  private WechatBindLinkEntity fail(WechatRebindOperationEntity operation, String rawError) {
    String safeError = sanitize(rawError);
    transactions.executeWithoutResult(status -> {
      operation.setStatus("cleanup_failed");
      operation.setLastError(safeError);
      operation.setUpdatedAt(now());
      operationMapper.update(operation);
      WechatBindLinkEntity link = requireLink(operation.getBindToken());
      link.setStatus("cleanup_failed");
      link.setCleanupStage(operation.getStage());
      link.setCleanupError(safeError);
      link.setErrorMessage("重新绑定清理失败，可由管理员重试。");
      link.setUpdatedAt(now());
      linkMapper.update(link);
    });
    return linkMapper.findByToken(operation.getBindToken());
  }

  private WechatBindLinkEntity requireLink(String token) {
    WechatBindLinkEntity link = linkMapper.findByTokenForUpdate(token);
    if (link == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "微信扫码链接不存在。");
    }
    return link;
  }

  private boolean before(WechatRebindOperationEntity operation, String target) {
    return stageIndex(operation.getStage()) < stageIndex(target);
  }

  private int stageIndex(String stage) {
    int index = STAGES.indexOf(text(stage));
    return index < 0 ? 0 : index;
  }

  private boolean sameInstance(WechatRebindOperationEntity operation) {
    return Objects.equals(operation.getOldInstanceId(), operation.getNewInstanceId());
  }

  private List<String> accountIds(WechatRebindOperationEntity operation) {
    List<String> result = new ArrayList<>();
    if (!text(operation.getOldAccountId()).isBlank()) {
      result.add(operation.getOldAccountId());
    }
    if (!text(operation.getNewAccountId()).isBlank() && !result.contains(operation.getNewAccountId())) {
      result.add(operation.getNewAccountId());
    }
    return result;
  }

  private List<String> apiPeers(WechatRebindOperationEntity operation) {
    return readStringList(operation.getApiPeerIdsJson());
  }

  private List<String> oldSessionIds(WechatRebindOperationEntity operation) {
    return readStringList(operation.getOldSessionIdsJson());
  }

  private List<String> readStringList(String json) {
    if (text(json).isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("重新绑定任务快照损坏。", error);
    }
  }

  private <T> T readJson(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("重新绑定账号快照损坏。", error);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("无法保存重新绑定任务快照。", error);
    }
  }

  private void validateLockedOldAccount(
      WechatBindLinkEntity link,
      WechatPairedAccountEntity suppliedOldAccount,
      WechatPairedAccountEntity lockedOldAccount,
      String scannedWechatUserId
  ) {
    if (lockedOldAccount == null) {
      throw new ApiException(HttpStatus.CONFLICT, "历史微信账号已不存在，无法重新绑定。");
    }
    if (!Objects.equals(text(lockedOldAccount.getAccountId()), text(suppliedOldAccount.getAccountId()))
        || !Objects.equals(text(lockedOldAccount.getInstanceId()), text(suppliedOldAccount.getInstanceId()))
        || !Objects.equals(text(lockedOldAccount.getPhone()), text(link.getPhone()))
        || !Objects.equals(text(lockedOldAccount.getWechatUserId()), text(scannedWechatUserId))) {
      throw new ApiException(HttpStatus.CONFLICT, "历史微信账号已发生变化，请刷新后重新出码。");
    }
  }

  private void requireRebindInput(
      WechatBindLinkEntity link,
      WechatPairedAccountEntity oldAccount,
      InstanceEntity newInstance,
      String newAccountId,
      String scannedWechatUserId
  ) {
    if (link == null || oldAccount == null || newInstance == null
        || text(newAccountId).isBlank() || text(scannedWechatUserId).isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "重新绑定参数不完整。");
    }
    if (!Objects.equals(text(oldAccount.getWechatUserId()), text(scannedWechatUserId))) {
      throw new ApiException(HttpStatus.CONFLICT, "扫码微信与历史微信用户不一致。");
    }
  }

  private static String generateAgentId() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return "user_" + HexFormat.of().formatHex(bytes);
  }

  private static String sanitize(String value) {
    String normalized = text(value)
        .replaceAll("(?i)(token|key|secret|password|authorization)\\s*[=:]\\s*\\S+", "$1=[redacted]")
        .replaceAll("(?i)bearer\\s+\\S+", "Bearer [redacted]");
    if (normalized.isBlank()) {
      normalized = "重新绑定清理失败。";
    }
    return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
  }

  private static void redactTerminalAudit(WechatBindLinkEntity link) {
    link.setQrMode(null);
    link.setQrPayload(null);
    link.setQrLink(null);
    link.setQrExpiresAt(null);
    link.setScannedWechatUserId(null);
    link.setTargetAccountId(null);
    link.setMiniappOpenidHash(null);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String now() {
    return Instant.now().toString();
  }
}
