package com.clawbotforall.instance;

import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.miniapp.MiniappUserKeyMapper;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.trace.IntegrationTraceMapper;
import com.clawbotforall.web.ApiException;
import com.clawbotforall.wechat.WechatBindLinkMapper;
import com.clawbotforall.wechat.WechatRebindOperationMapper;
import com.clawbotforall.wechat.WechatLogSanitizer;
import com.clawbotforall.wechat.WechatUserCleanupOperationEntity;
import com.clawbotforall.wechat.WechatUserCleanupOperationMapper;
import com.clawbotforall.wechat.WechatUserCleanupService;
import com.clawbotforall.wechat.WechatUserResidueScanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 可恢复的实例删除编排；删除本地和数据库状态，但不删除 OpenViking 服务端记忆。 */
@Service
public class InstanceDeletionService {
  private static final Logger log = LoggerFactory.getLogger(InstanceDeletionService.class);
  private static final long CLEANUP_WAIT_TIMEOUT_MS = 30L * 60L * 1000L;
  private static final long CLEANUP_WAIT_INTERVAL_MS = 2_000L;

  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final InstanceDeleteOperationMapper operationMapper;
  private final MiniappUserBindingMapper miniappBindingMapper;
  private final MiniappUserKeyMapper miniappUserKeyMapper;
  private final WechatUserCleanupService cleanupService;
  private final WechatUserCleanupOperationMapper cleanupOperationMapper;
  private final WechatUserResidueScanner residueScanner;
  private final OpenClawRuntime openClawRuntime;
  private final InstanceFileService fileService;
  private final IntegrationTraceMapper traceMapper;
  private final WechatBindLinkMapper bindLinkMapper;
  private final WechatRebindOperationMapper rebindOperationMapper;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;
  private final ExecutorService executor;
  private final Set<String> scheduledOperationIds = ConcurrentHashMap.newKeySet();

  public InstanceDeletionService(
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      InstanceDeleteOperationMapper operationMapper,
      MiniappUserBindingMapper miniappBindingMapper,
      MiniappUserKeyMapper miniappUserKeyMapper,
      WechatUserCleanupService cleanupService,
      WechatUserCleanupOperationMapper cleanupOperationMapper,
      WechatUserResidueScanner residueScanner,
      OpenClawRuntime openClawRuntime,
      InstanceFileService fileService,
      IntegrationTraceMapper traceMapper,
      WechatBindLinkMapper bindLinkMapper,
      WechatRebindOperationMapper rebindOperationMapper,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager
  ) {
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.operationMapper = operationMapper;
    this.miniappBindingMapper = miniappBindingMapper;
    this.miniappUserKeyMapper = miniappUserKeyMapper;
    this.cleanupService = cleanupService;
    this.cleanupOperationMapper = cleanupOperationMapper;
    this.residueScanner = residueScanner;
    this.openClawRuntime = openClawRuntime;
    this.fileService = fileService;
    this.traceMapper = traceMapper;
    this.bindLinkMapper = bindLinkMapper;
    this.rebindOperationMapper = rebindOperationMapper;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.executor = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "instance-deletion");
      thread.setDaemon(true);
      return thread;
    });
  }

  public InstanceDeleteOperationEntity start(String instanceId, boolean force) {
    InstanceDeleteOperationEntity operation = Objects.requireNonNull(transactions.execute(status -> {
      InstanceEntity instance = aggregateMapper.findById(instanceId);
      if (instance == null) {
        throw new ApiException(HttpStatus.NOT_FOUND, "实例不存在。");
      }
      InstanceDeleteOperationEntity active = operationMapper.findActiveByInstanceForUpdate(instanceId);
      if (active != null) {
        if ((active.getWechatAccountCount() > 0 || active.getMiniappBindingCount() > 0) && !force && !active.isForce()) {
          throw conflict(active);
        }
        return active;
      }
      int wechatCount = aggregateMapper.countWechatAccountsByInstanceId(instanceId);
      int miniappCount = miniappBindingMapper.countByInstanceId(instanceId);
      if ((wechatCount > 0 || miniappCount > 0) && !force) {
        InstanceDeleteOperationEntity summary = newOperation(instance, false, wechatCount, miniappCount, "pending");
        throw conflict(summary);
      }
      InstanceDeleteOperationEntity created = newOperation(instance, force, wechatCount, miniappCount, "pending");
      operationMapper.insert(created);
      mutationMapper.updateInstanceStatus(instanceId, "deleting", created.getUpdatedAt());
      return created;
    }));
    schedule(operation.getOperationId());
    return operation;
  }

  public InstanceDeleteOperationEntity find(String operationId) {
    InstanceDeleteOperationEntity operation = operationMapper.findById(operationId);
    if (operation == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "实例删除任务不存在。");
    }
    return operation;
  }

  public InstanceDeleteOperationEntity retry(String operationId) {
    InstanceDeleteOperationEntity operation = Objects.requireNonNull(transactions.execute(status -> {
      InstanceDeleteOperationEntity locked = operationMapper.findByIdForUpdate(operationId);
      if (locked == null) {
        throw new ApiException(HttpStatus.NOT_FOUND, "实例删除任务不存在。");
      }
      if (!"delete_failed".equals(locked.getStatus())) {
        throw new ApiException(HttpStatus.CONFLICT, "只有失败的实例删除任务可以重试。");
      }
      locked.setStatus("pending");
      locked.setLastError(null);
      locked.setUpdatedAt(now());
      operationMapper.update(locked);
      return locked;
    }));
    schedule(operation.getOperationId());
    return operation;
  }

  private void schedule(String operationId) {
    if (!scheduledOperationIds.add(operationId)) {
      return;
    }
    try {
      executor.execute(() -> {
        try {
          execute(operationId);
        } finally {
          scheduledOperationIds.remove(operationId);
        }
      });
    } catch (RejectedExecutionException error) {
      scheduledOperationIds.remove(operationId);
      fail(operationId, error);
    }
  }

  private void execute(String operationId) {
    InstanceDeleteOperationEntity operation = markDeleting(operationId);
    InstanceEntity instance = aggregateMapper.findById(operation.getInstanceId());
    if (instance == null) {
      completeMissingInstance(operation);
      return;
    }
    try {
      mutationMapper.updateInstanceStatus(instance.getId(), "deleting", now());

      openClawRuntime.stopInstance(instance);
      advance(operation, "runtime_removed");

      List<String> cleanupOperationIds = new ArrayList<>();
      for (WechatUserCleanupOperationEntity cleanup : cleanupService.startAll(instance)) {
        cleanupOperationIds.add(cleanup.getOperationId());
      }
      saveCleanupIds(operation, cleanupOperationIds);
      advance(operation, "user_cleanups_started");
      waitForCleanups(cleanupOperationIds);
      advance(operation, "user_cleanups_completed");

      WechatUserResidueScanner.ScanResult scan = residueScanner.scanInstance(instance);
      for (String id : scan.operationIds()) {
        if (!cleanupOperationIds.contains(id)) {
          cleanupOperationIds.add(id);
        }
      }
      saveCleanupIds(operation, cleanupOperationIds);
      waitForCleanups(scan.operationIds());
      advance(operation, "ghost_residue_cleaned");

      traceMapper.deleteByInstanceId(instance.getId());

      fileService.deleteInstanceDirectory(instance.getId());
      advance(operation, "filesystem_deleted");

      removeResidualDatabaseRows(instance.getId());
      mutationMapper.deleteInstance(instance.getId());
      advance(operation, "database_deleted");

      redactHistory(instance.getId());
      advance(operation, "history_redacted");

      complete(operation);
    } catch (Exception error) {
      log.warn("实例删除任务失败：operationId={}, instanceId={}, stage={}, reason={}",
          operationId, operation.getInstanceId(), operation.getStage(), error.getMessage());
      log.debug("实例删除任务异常详情：operationId={}", operationId, error);
      fail(operationId, error);
    }
  }

  private void waitForCleanups(List<String> operationIds) throws InterruptedException {
    if (operationIds == null || operationIds.isEmpty()) {
      return;
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>(operationIds);
    long deadline = System.currentTimeMillis() + CLEANUP_WAIT_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      boolean allCompleted = true;
      for (String id : ids) {
        WechatUserCleanupOperationEntity cleanup = cleanupOperationMapper.findById(id);
        if (cleanup == null || "completed".equals(cleanup.getStatus())) {
          continue;
        }
        if ("cleanup_failed".equals(cleanup.getStatus()) || "cancelled".equals(cleanup.getStatus())) {
          throw new IllegalStateException("用户清理任务失败，已停止实例删除。cleanupOperationId=" + id);
        }
        allCompleted = false;
      }
      if (allCompleted) {
        return;
      }
      Thread.sleep(CLEANUP_WAIT_INTERVAL_MS);
    }
    throw new IllegalStateException("等待用户清理任务完成超时。");
  }

  private InstanceDeleteOperationEntity markDeleting(String operationId) {
    return Objects.requireNonNull(transactions.execute(status -> {
      InstanceDeleteOperationEntity operation = operationMapper.findByIdForUpdate(operationId);
      if (operation == null) {
        throw new ApiException(HttpStatus.NOT_FOUND, "实例删除任务不存在。");
      }
      if ("completed".equals(operation.getStatus())) {
        return operation;
      }
      operation.setStatus("deleting");
      operation.setLastError(null);
      operation.setUpdatedAt(now());
      operationMapper.update(operation);
      return operation;
    }));
  }

  private void advance(InstanceDeleteOperationEntity operation, String stage) {
    operation.setStage(stage);
    operation.setUpdatedAt(now());
    operationMapper.update(operation);
  }

  private void saveCleanupIds(InstanceDeleteOperationEntity operation, List<String> ids) {
    operation.setCleanupOperationIdsJson(writeJson(new ArrayList<>(new LinkedHashSet<>(ids))));
    operation.setUpdatedAt(now());
    operationMapper.update(operation);
  }

  private void complete(InstanceDeleteOperationEntity operation) {
    String at = now();
    operation.setStatus("completed");
    operation.setStage("completed");
    operation.setInstanceName(null);
    operation.setContainerName(null);
    operation.setCleanupOperationIdsJson(null);
    operation.setLastError(null);
    operation.setUpdatedAt(at);
    operation.setCompletedAt(at);
    operationMapper.update(operation);
  }

  private void completeMissingInstance(InstanceDeleteOperationEntity operation) {
    String at = now();
    operation.setStatus("completed");
    operation.setStage("completed");
    operation.setInstanceName(null);
    operation.setContainerName(null);
    operation.setCleanupOperationIdsJson(null);
    operation.setLastError(null);
    operation.setUpdatedAt(at);
    operation.setCompletedAt(at);
    operationMapper.update(operation);
  }

  private void fail(String operationId, Exception error) {
    transactions.executeWithoutResult(status -> {
      InstanceDeleteOperationEntity operation = operationMapper.findByIdForUpdate(operationId);
      if (operation == null || "completed".equals(operation.getStatus())) {
        return;
      }
      operation.setStatus("delete_failed");
      operation.setLastError(sanitize(error.getMessage() == null ? String.valueOf(error) : error.getMessage()));
      operation.setUpdatedAt(now());
      operationMapper.update(operation);
    });
  }

  private void redactHistory(String instanceId) {
    String updatedAt = now();
    bindLinkMapper.redactByInstanceId(instanceId, updatedAt);
    rebindOperationMapper.redactByInstanceId(instanceId, updatedAt);
    cleanupOperationMapper.redactByInstanceId(instanceId, updatedAt);
  }

  private void removeResidualDatabaseRows(String instanceId) {
    miniappUserKeyMapper.deleteByInstanceId(instanceId);
    miniappBindingMapper.deleteByInstanceId(instanceId);
    mutationMapper.deleteWechatAccountsForInstance(instanceId);
  }

  private InstanceDeleteOperationEntity newOperation(
      InstanceEntity instance, boolean force, int wechatCount, int miniappCount, String status) {
    String now = now();
    InstanceDeleteOperationEntity operation = new InstanceDeleteOperationEntity();
    operation.setOperationId(UUID.randomUUID().toString());
    operation.setInstanceId(instance.getId());
    operation.setInstanceName(instance.getName());
    operation.setContainerName(instance.getContainerName());
    operation.setForce(force);
    operation.setStatus(status);
    operation.setStage("validated");
    operation.setWechatAccountCount(wechatCount);
    operation.setMiniappBindingCount(miniappCount);
    operation.setCleanupOperationIdsJson("[]");
    operation.setCreatedAt(now);
    operation.setUpdatedAt(now);
    return operation;
  }

  private ApiException conflict(InstanceDeleteOperationEntity summary) {
    return new InstanceDeleteConflictException(summary);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? List.of() : value);
    } catch (JsonProcessingException error) {
      return "[]";
    }
  }

  private static String sanitize(String value) {
    String sanitized = (value == null ? "" : value.trim())
        .replaceAll("(?i)(token|key|secret|password|authorization)\\s*[=:]\\s*\\S+", "$1=[redacted]")
        .replaceAll("(?i)bearer\\s+\\S+", "Bearer [redacted]");
    if (sanitized.isBlank()) {
      sanitized = "实例删除失败。";
    }
    return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
  }

  private static String now() {
    return Instant.now().toString();
  }

  public static class InstanceDeleteConflictException extends ApiException {
    private final PublicInstanceDeleteOperation operation;

    InstanceDeleteConflictException(InstanceDeleteOperationEntity summary) {
      super(HttpStatus.CONFLICT, "该实例存在微信账号或小程序绑定，需要强确认后删除。");
      this.operation = PublicInstanceDeleteOperation.from(summary);
    }

    public PublicInstanceDeleteOperation getOperation() {
      return operation;
    }
  }
}
