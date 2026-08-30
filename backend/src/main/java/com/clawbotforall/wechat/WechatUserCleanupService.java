package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.miniapp.MiniappUserBindingEntity;
import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.miniapp.MiniappUserKeyMapper;
import com.clawbotforall.openviking.OpenVikingUserKeyMapper;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.trace.IntegrationTraceMapper;
import com.clawbotforall.useragent.UserAgentIdentityEntity;
import com.clawbotforall.useragent.UserAgentIdentityMapper;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 可恢复的用户解绑清理编排；只删除本地状态，不删除 OpenViking 服务端用户或记忆。 */
@Service
public class WechatUserCleanupService {
  private static final java.util.Set<String> STRONG_RESIDUE_EVIDENCE = java.util.Set.of(
      "identity_wechat_binding", "identity_agent_config", "binding_agent_peer",
      "miniapp_agent_instance", "cleanup_snapshot", "rebind_snapshot", "wechat_account_state"
  );
  private static final java.util.regex.Pattern AGENT_ID = java.util.regex.Pattern.compile("user_[0-9a-f]{32}");
  private static final List<String> STAGES = List.of(
      "validated", "channels_stopped", "routing_deleted", "local_agent_data_deleted",
      "wechat_files_deleted", "database_identity_deleted", "history_redacted",
      "gateway_restarted", "completed"
  );

  private final WechatUserCleanupOperationMapper operationMapper;
  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final UserAgentIdentityMapper identityMapper;
  private final MiniappUserBindingMapper miniappBindingMapper;
  private final MiniappUserKeyMapper miniappKeyMapper;
  private final OpenVikingUserKeyMapper openVikingUserKeyMapper;
  private final OpenClawGatewayRpcService gatewayRpcService;
  private final OpenClawUserDataCleaner dataCleaner;
  private final WechatAccountSyncService accountSyncService;
  private final WechatBindLinkMapper bindLinkMapper;
  private final WechatRebindOperationMapper rebindOperationMapper;
  private final IntegrationTraceMapper traceMapper;
  private final OpenClawRuntime openClawRuntime;
  private final InstanceFileService fileService;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;
  private final ExecutorService cleanupExecutor;
  private final ConcurrentHashMap<String, ReentrantLock> instanceCleanupLocks = new ConcurrentHashMap<>();
  private final java.util.Set<String> scheduledOperationIds = ConcurrentHashMap.newKeySet();

  public WechatUserCleanupService(
      WechatUserCleanupOperationMapper operationMapper,
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      UserAgentIdentityMapper identityMapper,
      MiniappUserBindingMapper miniappBindingMapper,
      MiniappUserKeyMapper miniappKeyMapper,
      OpenVikingUserKeyMapper openVikingUserKeyMapper,
      OpenClawGatewayRpcService gatewayRpcService,
      OpenClawUserDataCleaner dataCleaner,
      WechatAccountSyncService accountSyncService,
      WechatBindLinkMapper bindLinkMapper,
      WechatRebindOperationMapper rebindOperationMapper,
      IntegrationTraceMapper traceMapper,
      OpenClawRuntime openClawRuntime,
      InstanceFileService fileService,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager,
      @Qualifier("wechatUserCleanupExecutor") ExecutorService cleanupExecutor
  ) {
    this.operationMapper = operationMapper;
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.identityMapper = identityMapper;
    this.miniappBindingMapper = miniappBindingMapper;
    this.miniappKeyMapper = miniappKeyMapper;
    this.openVikingUserKeyMapper = openVikingUserKeyMapper;
    this.gatewayRpcService = gatewayRpcService;
    this.dataCleaner = dataCleaner;
    this.accountSyncService = accountSyncService;
    this.bindLinkMapper = bindLinkMapper;
    this.rebindOperationMapper = rebindOperationMapper;
    this.traceMapper = traceMapper;
    this.openClawRuntime = openClawRuntime;
    this.fileService = fileService;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.cleanupExecutor = cleanupExecutor;
  }

  public WechatUserCleanupOperationEntity start(InstanceEntity instance, String accountId, String source) {
    if (instance == null || text(instance.getId()).isBlank() || text(accountId).isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "解绑参数不完整。");
    }
    CaptureResult captured = Objects.requireNonNull(
        transactions.execute(status -> capture(instance, accountId, source)));
    if (captured.created()) {
      schedule(instance, captured.operation());
    }
    return captured.operation();
  }

  public WechatUserCleanupOperationEntity startResidue(
      InstanceEntity instance, WechatUserResidueEvidence evidence, String source) {
    String agentId = evidence == null ? "" : text(evidence.agentId());
    String accountId = evidence == null ? "" : text(evidence.accountId());
    boolean accountStateOnly = agentId.isBlank() && !accountId.isBlank()
        && evidence.evidenceTypes().stream().map(WechatUserCleanupService::text)
            .anyMatch("wechat_account_state"::equals);
    if (instance == null || text(instance.getId()).isBlank() || evidence == null
        || (agentId.isBlank() && !accountStateOnly)
        || (!agentId.isBlank() && !AGENT_ID.matcher(agentId).matches())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "幽灵残留清理参数不完整。");
    }
    boolean stronglyAttributed = evidence.evidenceTypes().stream()
        .map(WechatUserCleanupService::text)
        .anyMatch(STRONG_RESIDUE_EVIDENCE::contains);
    if (!stronglyAttributed) {
      throw new ApiException(HttpStatus.CONFLICT, "无法确认幽灵 Agent 的用户归属，已保留并等待人工核对。");
    }
    CaptureResult captured = Objects.requireNonNull(
        transactions.execute(status -> captureResidue(instance, evidence, source)));
    if (captured.created()) {
      schedule(instance, captured.operation());
    }
    return captured.operation();
  }

  public List<WechatUserCleanupOperationEntity> startAll(InstanceEntity instance) {
    if (instance == null || text(instance.getId()).isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "实例不能为空。");
    }
    List<WechatPairedAccountEntity> accounts = aggregateMapper.listWechatAccountsByInstanceIds(List.of(instance.getId()));
    if (accounts == null || accounts.isEmpty()) {
      return List.of();
    }
    java.util.ArrayList<WechatUserCleanupOperationEntity> operations = new java.util.ArrayList<>();
    for (WechatPairedAccountEntity account : accounts) {
      try {
        operations.add(start(instance, account.getAccountId(), "instance_unbind"));
      } catch (RuntimeException error) {
        operations.add(recordBatchFailure(instance, account, error));
      }
    }
    return List.copyOf(operations);
  }
  private WechatUserCleanupOperationEntity recordBatchFailure(
      InstanceEntity instance, WechatPairedAccountEntity account, RuntimeException error) {
    return Objects.requireNonNull(transactions.execute(status -> {
      String subjectHash = subjectHash(instance.getId(), account.getWechatUserId(), account.getPhone(),
          null, account.getAccountId());
      WechatUserCleanupOperationEntity active = operationMapper.findActiveByIdentityForUpdate(
          instance.getId(), account.getPhone(), account.getWechatUserId(), account.getAccountId(), null);
      if (active == null) {
        active = operationMapper.findActiveBySubjectForUpdate(instance.getId(), subjectHash);
      }
      if (active != null) {
        return active;
      }
      String now = now();
      WechatUserCleanupOperationEntity failed = new WechatUserCleanupOperationEntity();
      failed.setOperationId(UUID.randomUUID().toString());
      failed.setInstanceId(instance.getId());
      failed.setSource("instance_unbind");
      failed.setSubjectHash(subjectHash);
      failed.setPhone(account.getPhone());
      failed.setWechatUserId(account.getWechatUserId());
      failed.setAccountId(account.getAccountId());
      failed.setApiPeerIdsJson("[]");
      failed.setOldSessionIdsJson("[]");
      failed.setProtectedAgentIdsJson("[]");
      failed.setSnapshotJson(writeJson(Map.of(
          "instanceId", text(instance.getId()),
          "accountId", text(account.getAccountId()),
          "wechatUserId", text(account.getWechatUserId()),
          "captureFailed", true
      )));
      failed.setStatus("cleanup_failed");
      failed.setStage("validated");
      failed.setAttemptCount(1);
      failed.setLastError(sanitize(error.getMessage()));
      failed.setCreatedAt(now);
      failed.setUpdatedAt(now);
      operationMapper.insert(failed);
      return failed;
    }));
  }

  public WechatUserCleanupOperationEntity resume(String operationId) {
    if (text(operationId).isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "清理任务 ID 不能为空。");
    }
    if (scheduledOperationIds.contains(operationId)) {
      WechatUserCleanupOperationEntity scheduled = operationMapper.findById(operationId);
      if (scheduled != null) {
        return scheduled;
      }
    }
    WechatUserCleanupOperationEntity operation = transactions.execute(status -> {
      WechatUserCleanupOperationEntity locked = operationMapper.findByIdForUpdate(operationId);
      if (locked == null) {
        throw new ApiException(HttpStatus.NOT_FOUND, "用户清理任务不存在。");
      }
      if (!java.util.Set.of("pending", "cleaning").contains(text(locked.getStatus()))) {
        throw new ApiException(HttpStatus.CONFLICT, "只有中断的清理任务可以自动恢复。");
      }
      locked.setStatus("cleaning");
      locked.setAttemptCount(locked.getAttemptCount() + 1);
      locked.setLastError(null);
      locked.setUpdatedAt(now());
      operationMapper.update(locked);
      return locked;
    });
    InstanceEntity instance = aggregateMapper.findById(Objects.requireNonNull(operation).getInstanceId());
    if (instance == null) {
      fail(operation, new IllegalStateException("清理任务所属实例不存在。"));
      return operation;
    }
    schedule(instance, operation);
    return operation;
  }

  public WechatUserCleanupOperationEntity retry(String operationId) {
    if (text(operationId).isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "清理任务 ID 不能为空。");
    }
    WechatUserCleanupOperationEntity operation = transactions.execute(status -> {
      WechatUserCleanupOperationEntity locked = operationMapper.findByIdForUpdate(operationId);
      if (locked == null) {
        throw new ApiException(HttpStatus.NOT_FOUND, "用户清理任务不存在。");
      }
      if (!"cleanup_failed".equals(locked.getStatus())) {
        throw new ApiException(HttpStatus.CONFLICT, "只有清理失败的任务可以重试。");
      }
      locked.setStatus("cleaning");
      locked.setAttemptCount(locked.getAttemptCount() + 1);
      locked.setLastError(null);
      locked.setUpdatedAt(now());
      operationMapper.update(locked);
      return locked;
    });
    InstanceEntity instance = aggregateMapper.findById(Objects.requireNonNull(operation).getInstanceId());
    if (instance == null) {
      fail(operation, new IllegalStateException("清理任务所属实例不存在。"));
      return operation;
    }
    schedule(instance, operation);
    return operation;
  }

  public WechatUserCleanupOperationEntity find(String operationId) {
    WechatUserCleanupOperationEntity operation = operationMapper.findById(operationId);
    if (operation == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "用户清理任务不存在。");
    }
    return operation;
  }
  private CaptureResult capture(InstanceEntity instance, String accountId, String source) {
    WechatPairedAccountEntity account = aggregateMapper.findWechatAccountByAccountIdForUpdate(accountId);
    if (account == null || !Objects.equals(instance.getId(), account.getInstanceId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "微信绑定账号不存在。");
    }
    WechatBindLinkEntity activeBind = bindLinkMapper.findActiveForUserForUpdate(
        instance.getId(), account.getPhone(), account.getAccountId(), account.getWechatUserId(), now());
    if (activeBind != null) {
      throw new ApiException(HttpStatus.CONFLICT, "该用户正在扫码绑定，暂时不能解绑。");
    }
    WechatRebindOperationEntity activeRebind =
        rebindOperationMapper.findActiveForUserForUpdate(account.getPhone(), account.getWechatUserId());
    if (activeRebind != null) {
      throw new ApiException(HttpStatus.CONFLICT, "该用户正在重新绑定，暂时不能解绑。");
    }
    UserAgentIdentityEntity identity = identityMapper.findByWechatUserIdForUpdate(account.getWechatUserId());
    List<String> apiPeers = identity == null ? List.of() : miniappBindingMapper.listByAgentId(identity.getAgentId()).stream()
        .map(MiniappUserBindingEntity::getOpenidHash)
        .filter(value -> value != null && !value.isBlank())
        .map(value -> "api:" + value.trim())
        .distinct()
        .toList();
    List<String> sessions = identity == null ? List.of()
        : dataCleaner.readOldSessionIds(instance.getId(), identity.getAgentId());
    String subjectHash = subjectHash(instance.getId(), account, identity);
    WechatUserCleanupOperationEntity active = operationMapper.findActiveByIdentityForUpdate(
        instance.getId(), account.getPhone(), account.getWechatUserId(), account.getAccountId(),
        identity == null ? null : identity.getAgentId());
    if (active == null) {
      active = operationMapper.findActiveBySubjectForUpdate(instance.getId(), subjectHash);
    }
    if (active != null) {
      return new CaptureResult(active, false);
    }
    String now = now();
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId(UUID.randomUUID().toString());
    operation.setInstanceId(instance.getId());
    operation.setSource(text(source).isBlank() ? "user_center" : text(source));
    operation.setSubjectHash(subjectHash);
    operation.setPhone(account.getPhone());
    operation.setWechatUserId(account.getWechatUserId());
    operation.setAccountId(account.getAccountId());
    operation.setAgentId(identity == null ? null : identity.getAgentId());
    operation.setOpenvikingUserId(identity == null ? null : identity.getOpenvikingUserId());
    operation.setApiPeerIdsJson(writeJson(apiPeers));
    operation.setOldSessionIdsJson(writeJson(sessions));
    operation.setProtectedAgentIdsJson("[]");
    operation.setSnapshotJson(writeJson(Map.of(
        "instanceId", text(account.getInstanceId()),
        "accountId", text(account.getAccountId()),
        "wechatUserId", text(account.getWechatUserId()),
        "agentId", identity == null ? "" : text(identity.getAgentId()),
        "instanceRunningBeforeCleanup", openClawRuntime.inspectInstance(instance).running()
    )));
    operation.setStatus("cleaning");
    operation.setStage("validated");
    operation.setAttemptCount(1);
    operation.setCreatedAt(now);
    operation.setUpdatedAt(now);
    operationMapper.insert(operation);
    return new CaptureResult(operation, true);
  }

  private CaptureResult captureResidue(
      InstanceEntity instance, WechatUserResidueEvidence evidence, String source) {
    String subjectHash = subjectHash(instance.getId(), evidence.wechatUserId(), null, evidence.agentId(), evidence.accountId());
    WechatUserCleanupOperationEntity active = operationMapper.findActiveByIdentityForUpdate(
        instance.getId(), null, evidence.wechatUserId(), evidence.accountId(), evidence.agentId());
    if (active == null) {
      active = operationMapper.findActiveBySubjectForUpdate(instance.getId(), subjectHash);
    }
    if (active != null) {
      return new CaptureResult(active, false);
    }
    boolean accountStateOnly = text(evidence.agentId()).isBlank()
        && evidence.evidenceTypes().stream().map(WechatUserCleanupService::text)
            .anyMatch("wechat_account_state"::equals);
    if (accountStateOnly) {
      WechatPairedAccountEntity persisted = aggregateMapper.findWechatAccountByAccountId(evidence.accountId());
      if (persisted != null) {
        throw new ApiException(HttpStatus.CONFLICT, "微信账号已落库，不能按幽灵凭证清理。");
      }
      List<String> protectedAccountIds = bindLinkMapper.listProtectedAccountIds(instance.getId(), now());
      if (protectedAccountIds != null && protectedAccountIds.contains(text(evidence.accountId()))) {
        throw new ApiException(HttpStatus.CONFLICT, "微信账号仍处于绑定或清理流程，已跳过幽灵清理。");
      }
    }
    String now = now();
    WechatUserCleanupOperationEntity operation = new WechatUserCleanupOperationEntity();
    operation.setOperationId(UUID.randomUUID().toString());
    operation.setInstanceId(instance.getId());
    operation.setSource(text(source).isBlank() ? "residue_scanner" : text(source));
    operation.setSubjectHash(subjectHash);
    operation.setWechatUserId(blankToNull(evidence.wechatUserId()));
    operation.setAccountId(blankToNull(evidence.accountId()));
    operation.setAgentId(blankToNull(evidence.agentId()));
    operation.setOpenvikingUserId(blankToNull(evidence.openvikingUserId()));
    operation.setApiPeerIdsJson(writeJson(normalized(evidence.apiPeerIds())));
    operation.setOldSessionIdsJson(writeJson(normalized(evidence.sessionIds())));
    operation.setProtectedAgentIdsJson(writeJson(normalized(evidence.protectedAgentIds())));
    operation.setSnapshotJson(writeJson(Map.of(
        "instanceId", instance.getId(),
        "accountId", text(evidence.accountId()),
        "wechatUserId", text(evidence.wechatUserId()),
        "agentId", text(evidence.agentId()),
        "evidenceTypes", normalized(evidence.evidenceTypes()),
        "instanceRunningBeforeCleanup", openClawRuntime.inspectInstance(instance).running()
    )));
    operation.setStatus("cleaning");
    operation.setStage("validated");
    operation.setAttemptCount(1);
    operation.setCreatedAt(now);
    operation.setUpdatedAt(now);
    operationMapper.insert(operation);
    return new CaptureResult(operation, true);
  }


  private void schedule(InstanceEntity instance, WechatUserCleanupOperationEntity operation) {
    String operationId = text(operation == null ? null : operation.getOperationId());
    if (operationId.isBlank() || !scheduledOperationIds.add(operationId)) {
      return;
    }
    try {
      cleanupExecutor.execute(() -> {
        try {
          WechatUserCleanupOperationEntity latest = operationMapper.findById(operationId);
          if (latest == null || !java.util.Set.of("pending", "cleaning").contains(text(latest.getStatus()))) {
            return;
          }
          execute(instance, latest);
        } finally {
          scheduledOperationIds.remove(operationId);
        }
      });
    } catch (RejectedExecutionException error) {
      scheduledOperationIds.remove(operationId);
      fail(operation, new IllegalStateException("用户清理任务队列繁忙，请稍后重试。", error));
    }
  }

  private WechatUserCleanupOperationEntity execute(InstanceEntity instance, WechatUserCleanupOperationEntity operation) {
    ReentrantLock lock = instanceCleanupLocks.computeIfAbsent(instance.getId(), ignored -> new ReentrantLock());
    lock.lock();
    try {
      return executeLocked(instance, operation);
    } finally {
      lock.unlock();
    }
  }

  private WechatUserCleanupOperationEntity executeLocked(
      InstanceEntity instance, WechatUserCleanupOperationEntity operation) {
    try {
      hydrateMissingAgentEvidence(operation);
      if (before(operation, "channels_stopped")) {
        gatewayRpcService.stopWechatChannel(instance, accountIds(operation));
        advance(operation, "channels_stopped");
      }
      if (before(operation, "routing_deleted")) {
        if (!text(operation.getAgentId()).isBlank()) {
          OpenClawGatewayRpcService.DeleteUserAgentResult result = gatewayRpcService.deleteUserAgent(
              instance, operation.getAgentId(), accountIds(operation), peerIds(operation), apiPeers(operation),
              protectedAgentIds(operation));
          if (!result.conflictingBindings().isEmpty()) {
            throw new IllegalStateException("OpenClaw 路由冲突，旧 Agent 仍被其他 binding 引用。");
          }
          if (!result.success()) {
            throw new IllegalStateException("OpenClaw 用户路由或 Agent 配置删除失败。");
          }
          operation.setDeletedBindings(result.removedBindings().size());
        }
        advance(operation, "routing_deleted");
      }
      if (before(operation, "local_agent_data_deleted")) {
        if (!text(operation.getAgentId()).isBlank()) {
          dataCleaner.deleteOldUserData(operation.getInstanceId(), operation.getAgentId(), sessions(operation), apiPeers(operation));
          operation.setDeletedFiles(operation.getDeletedFiles() + 1);
        }
        advance(operation, "local_agent_data_deleted");
      }
      if (before(operation, "wechat_files_deleted")) {
        for (String accountId : accountIds(operation)) {
          accountSyncService.removeAccountStateFiles(fileService.paths(operation.getInstanceId()), accountId);
          operation.setDeletedFiles(operation.getDeletedFiles() + 1);
        }
        advance(operation, "wechat_files_deleted");
      }
      if (before(operation, "database_identity_deleted")) {
        int deletedRows = Objects.requireNonNull(transactions.execute(status -> deleteDatabaseIdentity(operation)));
        operation.setDeletedDatabaseRows(deletedRows);
        advance(operation, "database_identity_deleted");
      }
      if (before(operation, "history_redacted")) {
        redactHistory(operation);
        advance(operation, "history_redacted");
      }
      if (before(operation, "gateway_restarted")) {
        if (wasInstanceRunningBeforeCleanup(operation)) {
          List<String> remainingAccountIds = accountSyncService.syncInstanceAccounts(instance).stream()
              .map(WechatPairedAccountEntity::getAccountId)
              .map(WechatUserCleanupService::text)
              .filter(value -> !value.isBlank())
              .distinct()
              .toList();
          if (!remainingAccountIds.isEmpty()) {
            gatewayRpcService.startWechatChannel(instance, remainingAccountIds);
          }
        }
        advance(operation, "gateway_restarted");
      }
      complete(operation);
    } catch (RuntimeException error) {
      fail(operation, error);
    }
    return operation;
  }

  private void hydrateMissingAgentEvidence(WechatUserCleanupOperationEntity operation) {
    if (hasSnapshotEvidence(operation, "wechat_account_state")
        || !text(operation.getAgentId()).isBlank()
        || (text(operation.getAccountId()).isBlank() && text(operation.getWechatUserId()).isBlank())) {
      return;
    }
    Path configPath = fileService.paths(operation.getInstanceId()).homeDir().resolve("openclaw.json");
    if (!Files.exists(configPath)) {
      return;
    }
    java.util.Set<String> matchingAgents = new java.util.LinkedHashSet<>();
    try {
      JsonNode root = objectMapper.readTree(configPath.toFile());
      for (JsonNode binding : root.path("bindings")) {
        JsonNode match = binding.path("match");
        if (!"openclaw-weixin".equals(text(match.path("channel").asText()))) {
          continue;
        }
        boolean sameAccount = !text(operation.getAccountId()).isBlank()
            && text(operation.getAccountId()).equals(text(match.path("accountId").asText()));
        boolean samePeer = !text(operation.getWechatUserId()).isBlank()
            && text(operation.getWechatUserId()).equals(text(match.path("peer").path("id").asText()));
        String candidate = text(binding.path("agentId").asText());
        if ((sameAccount || samePeer) && AGENT_ID.matcher(candidate).matches()) {
          matchingAgents.add(candidate);
        }
      }
    } catch (IOException error) {
      throw new IllegalStateException("读取 OpenClaw 用户路由失败。", error);
    }
    if (matchingAgents.size() > 1) {
      throw new IllegalStateException("同一微信用户匹配到多个 Agent，无法安全清理。");
    }
    if (matchingAgents.isEmpty()) {
      return;
    }
    String agentId = matchingAgents.iterator().next();
    List<String> apiPeers = safeMiniappBindings(agentId).stream()
        .map(MiniappUserBindingEntity::getOpenidHash)
        .filter(value -> value != null && !value.isBlank())
        .map(value -> "api:" + value.trim())
        .distinct()
        .toList();
    operation.setAgentId(agentId);
    operation.setApiPeerIdsJson(writeJson(apiPeers));
    if (text(operation.getProtectedAgentIdsJson()).isBlank()) {
      operation.setProtectedAgentIdsJson("[]");
    }
    operation.setOldSessionIdsJson(writeJson(dataCleaner.readOldSessionIds(operation.getInstanceId(), agentId)));
    Map<String, Object> snapshot = snapshotValues(operation);
    snapshot.put("instanceId", text(operation.getInstanceId()));
    snapshot.put("accountId", text(operation.getAccountId()));
    snapshot.put("wechatUserId", text(operation.getWechatUserId()));
    snapshot.put("agentId", agentId);
    snapshot.put("inferredFromBinding", true);
    operation.setSnapshotJson(writeJson(snapshot));
    operation.setUpdatedAt(now());
    operationMapper.update(operation);
  }

  private Map<String, Object> snapshotValues(WechatUserCleanupOperationEntity operation) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (text(operation.getSnapshotJson()).isBlank()) {
      return values;
    }
    try {
      values.putAll(objectMapper.readValue(
          operation.getSnapshotJson(), new TypeReference<Map<String, Object>>() {}));
      return values;
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("用户清理任务快照损坏。", error);
    }
  }

  private boolean wasInstanceRunningBeforeCleanup(WechatUserCleanupOperationEntity operation) {
    if (text(operation.getSnapshotJson()).isBlank()) {
      return false;
    }
    try {
      return objectMapper.readTree(operation.getSnapshotJson())
          .path("instanceRunningBeforeCleanup").asBoolean(false);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("用户清理任务快照损坏。", error);
    }
  }

  private boolean hasSnapshotEvidence(WechatUserCleanupOperationEntity operation, String evidenceType) {
    if (text(operation.getSnapshotJson()).isBlank()) {
      return false;
    }
    try {
      for (JsonNode value : objectMapper.readTree(operation.getSnapshotJson()).path("evidenceTypes")) {
        if (evidenceType.equals(text(value.asText()))) {
          return true;
        }
      }
      return false;
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("用户清理任务快照损坏。", error);
    }
  }

  private List<MiniappUserBindingEntity> safeMiniappBindings(String agentId) {
    List<MiniappUserBindingEntity> bindings = miniappBindingMapper.listByAgentId(agentId);
    return bindings == null ? List.of() : bindings;
  }

  private int deleteDatabaseIdentity(WechatUserCleanupOperationEntity operation) {
    int deleted = 0;
    List<String> senderHashes = apiPeers(operation).stream()
        .map(value -> value.startsWith("api:") ? value.substring("api:".length()) : value)
        .filter(value -> !value.isBlank())
        .distinct()
        .toList();
    List<String> sessionKeyHashes = sessions(operation).stream().filter(value -> !value.isBlank()).distinct().toList();
    if (!senderHashes.isEmpty() || !sessionKeyHashes.isEmpty()) {
      deleted += traceMapper.deleteByIdentityEvidence(operation.getInstanceId(), senderHashes, sessionKeyHashes);
    }
    if (!text(operation.getAgentId()).isBlank()) {
      deleted += miniappKeyMapper.deleteByAgentId(operation.getAgentId());
      deleted += miniappBindingMapper.deleteByAgentId(operation.getAgentId());
    }
    if (!text(operation.getOpenvikingUserId()).isBlank()) {
      deleted += openVikingUserKeyMapper.deleteByOpenvikingUserId(operation.getOpenvikingUserId());
    }
    if (!text(operation.getAgentId()).isBlank()) {
      deleted += identityMapper.deleteByAgentId(operation.getAgentId());
    }
    if (!text(operation.getAccountId()).isBlank()) {
      deleted += mutationMapper.deleteWechatAccount(operation.getInstanceId(), operation.getAccountId());
    }
    return deleted;
  }

  private void redactHistory(WechatUserCleanupOperationEntity operation) {
    String updatedAt = now();
    bindLinkMapper.redactByPhoneOrAccountId(operation.getPhone(), operation.getAccountId(), updatedAt);
    rebindOperationMapper.redactForCleanup(
        operation.getPhone(), operation.getWechatUserId(), operation.getAccountId(), operation.getAgentId(), updatedAt);
  }

  private void advance(WechatUserCleanupOperationEntity operation, String stage) {
    operation.setStatus("cleaning");
    operation.setStage(stage);
    operation.setLastError(null);
    operation.setUpdatedAt(now());
    operationMapper.update(operation);
  }

  private void complete(WechatUserCleanupOperationEntity operation) {
    String now = now();
    operation.setPhone(null);
    operation.setWechatUserId(null);
    operation.setAccountId(null);
    operation.setAgentId(null);
    operation.setOpenvikingUserId(null);
    operation.setApiPeerIdsJson(null);
    operation.setOldSessionIdsJson(null);
    operation.setProtectedAgentIdsJson(null);
    operation.setSnapshotJson(null);
    operation.setStatus("completed");
    operation.setStage("completed");
    operation.setLastError(null);
    operation.setUpdatedAt(now);
    operation.setCompletedAt(now);
    operationMapper.update(operation);
  }

  private void fail(WechatUserCleanupOperationEntity operation, RuntimeException error) {
    operation.setStatus("cleanup_failed");
    operation.setLastError(sanitize(error.getMessage()));
    operation.setUpdatedAt(now());
    operationMapper.update(operation);
  }

  private List<String> accountIds(WechatUserCleanupOperationEntity operation) {
    return text(operation.getAccountId()).isBlank() ? List.of() : List.of(operation.getAccountId());
  }

  private List<String> peerIds(WechatUserCleanupOperationEntity operation) {
    return text(operation.getWechatUserId()).isBlank() ? List.of() : List.of(operation.getWechatUserId());
  }

  private List<String> apiPeers(WechatUserCleanupOperationEntity operation) {
    return readStringList(operation.getApiPeerIdsJson());
  }

  private List<String> sessions(WechatUserCleanupOperationEntity operation) {
    return readStringList(operation.getOldSessionIdsJson());
  }

  private boolean before(WechatUserCleanupOperationEntity operation, String target) {
    return stageIndex(operation.getStage()) < stageIndex(target);
  }

  private int stageIndex(String stage) {
    int index = STAGES.indexOf(text(stage));
    return index < 0 ? 0 : index;
  }

  private List<String> protectedAgentIds(WechatUserCleanupOperationEntity operation) {
    return readStringList(operation.getProtectedAgentIdsJson());
  }

  private List<String> readStringList(String json) {
    if (text(json).isBlank()) return List.of();
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("用户清理任务快照损坏。", error);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("无法保存用户清理任务快照。", error);
    }
  }

  private static String subjectHash(
      String instanceId, WechatPairedAccountEntity account, UserAgentIdentityEntity identity) {
    return subjectHash(instanceId, account.getWechatUserId(), account.getPhone(),
        identity == null ? null : identity.getAgentId(), account.getAccountId());
  }

  private static String subjectHash(
      String instanceId, String wechatUserId, String phone, String agentId, String accountId) {
    String strongest = !text(wechatUserId).isBlank() ? wechatUserId
        : !text(phone).isBlank() ? phone
        : !text(agentId).isBlank() ? agentId
        : accountId;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest((instanceId + "\0" + text(strongest)).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 不可用。", impossible);
    }
  }

  private static List<String> normalized(List<String> values) {
    if (values == null) return List.of();
    return values.stream().map(WechatUserCleanupService::text).filter(value -> !value.isBlank()).distinct().toList();
  }

  private static String blankToNull(String value) {
    String normalized = text(value);
    return normalized.isBlank() ? null : normalized;
  }

  private static String sanitize(String value) {
    String sanitized = text(value)
        .replaceAll("(?i)(token|key|secret|password|authorization)\\s*[=:]\\s*\\S+", "$1=[redacted]")
        .replaceAll("(?i)bearer\\s+\\S+", "Bearer [redacted]");
    if (sanitized.isBlank()) sanitized = "用户清理失败。";
    return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
  }

  private static String text(String value) { return value == null ? "" : value.trim(); }
  private static String now() { return Instant.now().toString(); }
  private record CaptureResult(WechatUserCleanupOperationEntity operation, boolean created) {}

}


