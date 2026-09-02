package com.clawbotforall.wechat;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.WechatAccountChannelEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 管理员扫码链接、新用户手机号绑定和老用户回原实例绑定流程服务。
 */
@Service
public class WechatBindLinkService {

  private static final Logger log = LoggerFactory.getLogger(WechatBindLinkService.class);

  private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
  private static final Duration LINK_TTL = Duration.ofDays(1);

  private final WechatBindLinkMapper linkMapper;
  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final WechatBindService wechatBindService;
  private final WechatAccountSyncService accountSyncService;
  private final InstanceFileService fileService;
  private final ClawbotProperties properties;
  private final ObjectMapper objectMapper;
  private final InstanceEventPublisher eventPublisher;
  private final OpenClawGatewayRpcService gatewayRpcService;
  private final WechatUserRebindService rebindService;
  private final ApplicationEventPublisher bindEventPublisher;
  private final Executor executor;
  private final ConcurrentMap<String, Boolean> qrJobs = new ConcurrentHashMap<>();

  @Autowired
  public WechatBindLinkService(
      WechatBindLinkMapper linkMapper,
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      WechatBindService wechatBindService,
      WechatAccountSyncService accountSyncService,
      InstanceFileService fileService,
      ClawbotProperties properties,
      ObjectMapper objectMapper,
      InstanceEventPublisher eventPublisher,
      OpenClawGatewayRpcService gatewayRpcService,
      WechatUserRebindService rebindService,
      ApplicationEventPublisher bindEventPublisher
  ) {
    this(
        linkMapper,
        aggregateMapper,
        mutationMapper,
        wechatBindService,
        accountSyncService,
        fileService,
        properties,
        objectMapper,
        eventPublisher,
        gatewayRpcService,
        rebindService,
        bindEventPublisher,
        defaultExecutor()
    );
  }

  WechatBindLinkService(
      WechatBindLinkMapper linkMapper,
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      WechatBindService wechatBindService,
      WechatAccountSyncService accountSyncService,
      InstanceFileService fileService,
      ClawbotProperties properties,
      ObjectMapper objectMapper,
      InstanceEventPublisher eventPublisher,
      OpenClawGatewayRpcService gatewayRpcService,
      WechatUserRebindService rebindService,
      Executor executor
  ) {
    this(
        linkMapper,
        aggregateMapper,
        mutationMapper,
        wechatBindService,
        accountSyncService,
        fileService,
        properties,
        objectMapper,
        eventPublisher,
        gatewayRpcService,
        rebindService,
        ignored -> {},
        executor
    );
  }

  WechatBindLinkService(
      WechatBindLinkMapper linkMapper,
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      WechatBindService wechatBindService,
      WechatAccountSyncService accountSyncService,
      InstanceFileService fileService,
      ClawbotProperties properties,
      ObjectMapper objectMapper,
      InstanceEventPublisher eventPublisher,
      OpenClawGatewayRpcService gatewayRpcService,
      WechatUserRebindService rebindService,
      ApplicationEventPublisher bindEventPublisher,
      Executor executor
  ) {
    this.linkMapper = linkMapper;
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.wechatBindService = wechatBindService;
    this.accountSyncService = accountSyncService;
    this.fileService = fileService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.eventPublisher = eventPublisher;
    this.gatewayRpcService = gatewayRpcService;
    this.rebindService = rebindService;
    this.bindEventPublisher = bindEventPublisher;
    this.executor = executor;
  }

  /**
   * 管理员生成新用户或老用户扫码链接。
   */
  @Transactional
  public PublicWechatBindLink createLink(
      AuthenticatedAdmin admin,
      CreateBindLinkRequest request,
      String origin
  ) {
    String mode = normalizeMode(request == null ? "" : request.mode());
    String now = Instant.now().toString();
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setToken(randomToken());
    link.setMode(mode);
    link.setStatus("created");
    link.setCreatedByAdminId(admin.id());
    link.setCreatedAt(now);
    link.setExpiresAt(Instant.parse(now).plus(LINK_TTL).toString());
    link.setUpdatedAt(now);

    if ("existing".equals(mode)) {
      String phone = normalizePhone(request.phone());
      WechatPairedAccountEntity account = requireBindingByPhone(phone);
      InstanceEntity instance = requireBindableInstance(
          account.getInstanceId(),
          "该用户绑定的 OpenClaw 实例暂不可用，请先启动并等待实例就绪。"
      );
      link.setPhone(phone);
      link.setInstanceId(instance.getId());
      link.setTargetAccountId(account.getAccountId());
    } else {
      String phone = normalizePhone(request == null ? "" : request.phone());
      if (aggregateMapper.findWechatAccountByPhone(phone) != null) {
        throw new ApiException(HttpStatus.CONFLICT, "该手机号已绑定，请使用老用户出码。");
      }
      InstanceEntity instance = chooseBindableInstance();
      link.setPhone(phone);
      link.setInstanceId(instance.getId());
      link.setTargetAccountId(targetAccountIdFromToken(link.getToken()));
    }

    linkMapper.insert(link);
    log.info("管理员创建微信扫码链接：mode={}, instanceId={}", link.getMode(), defaultString(link.getInstanceId()));
    scheduleQrAfterCommit(link.getToken(), false, origin);
    return publicLink(link, origin);
  }

  @Transactional
  public PublicWechatBindLink createMiniappLink(
      String miniappOpenidHash,
      String instanceId,
      String targetAccountId,
      String origin
  ) {
    String normalizedHash = defaultString(miniappOpenidHash).trim();
    if (normalizedHash.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "小程序 openid hash 不能为空。");
    }
    InstanceEntity instance = requireBindableInstance(
        instanceId,
        "该小程序用户绑定的 OpenClaw 实例暂不可用，请先启动并等待实例就绪。"
    );
    String normalizedTargetAccountId = defaultString(targetAccountId).trim();
    String now = Instant.now().toString();
    WechatBindLinkEntity existing = linkMapper.findActiveMiniappLinkForUpdate(
        normalizedHash, instance.getId(), normalizedTargetAccountId, now);
    if (existing != null && isReusableMiniappLink(existing)) {
      log.info("小程序复用微信扫码链接：instanceId={}, openidHash={}, status={}",
          instance.getId(), normalizedHash, defaultString(existing.getStatus()));
      return publicLink(existing, origin);
    }
    WechatBindLinkEntity link = new WechatBindLinkEntity();
    link.setToken(randomToken());
    link.setMode(hasText(normalizedTargetAccountId) ? "existing" : "new");
    link.setPhone(null);
    link.setInstanceId(instance.getId());
    link.setTargetAccountId(hasText(normalizedTargetAccountId) ? normalizedTargetAccountId : targetAccountIdFromToken(link.getToken()));
    link.setMiniappOpenidHash(normalizedHash);
    link.setStatus("created");
    link.setCreatedAt(now);
    link.setExpiresAt(Instant.parse(now).plus(LINK_TTL).toString());
    link.setUpdatedAt(now);
    linkMapper.insert(link);
    log.info("小程序创建微信扫码链接：instanceId={}, openidHash={}", instance.getId(), normalizedHash);
    scheduleQrAfterCommit(link.getToken(), false, origin);
    return publicLink(link, origin);
  }

  /**
   * 管理员按手机号查询已绑定关系。
   */
  @Transactional(readOnly = true)
  public WechatPairedAccountEntity findBindingByPhone(String phone) {
    return aggregateMapper.findWechatAccountByPhone(normalizePhone(phone));
  }

  /**
   * 管理员按手机号片段搜索已绑定关系。
   */
  @Transactional(readOnly = true)
  public List<WechatPairedAccountEntity> searchBindingsByPhoneKeyword(String phoneKeyword) {
    String keyword = normalizePhoneKeyword(phoneKeyword);
    if (keyword.isBlank()) {
      return List.of();
    }
    return aggregateMapper.searchWechatAccountsByPhoneKeyword(keyword);
  }

  /**
   * 管理员查询扫码链接历史。
   */
  @Transactional(readOnly = true)
  public AdminLinkPage listAdminLinks(
      String mode,
      String status,
      String phone,
      int page,
      int pageSize,
      String origin
  ) {
    String normalizedMode = normalizeOptionalMode(mode);
    String normalizedStatus = defaultString(status).trim();
    String normalizedPhone = normalizePhoneKeyword(phone);
    int normalizedPageSize = Math.max(5, Math.min(100, pageSize));
    int normalizedPage = Math.max(1, page);
    int offset = (normalizedPage - 1) * normalizedPageSize;
    String now = Instant.now().toString();
    List<PublicWechatBindLink> links = linkMapper
        .listAdminLinks(normalizedMode, normalizedStatus, normalizedPhone, now, offset, normalizedPageSize)
        .stream()
        .map(link -> publicLink(link, origin))
        .toList();
    int total = linkMapper.countAdminLinks(normalizedMode, normalizedStatus, normalizedPhone, now);
    return new AdminLinkPage(links, total, normalizedPage, normalizedPageSize);
  }

  /**
   * 管理员读取单条扫码链接详情。
   */
  @Transactional(readOnly = true)
  public PublicWechatBindLink adminLinkDetail(String token, String origin) {
    return publicLink(requireLink(token), origin);
  }

  /**
   * 管理员手动失效扫码链接。
   */
  @Transactional
  public PublicWechatBindLink revokeLink(String token, String origin) {
    WechatBindLinkEntity link = requireLink(token);
    if (isCleanupStatus(link.getStatus())) {
      throw new ApiException(HttpStatus.CONFLICT, "重新绑定清理任务正在执行或等待重试，不能直接失效。");
    }
    cleanupTemporaryAccountState(link);
    String timestamp = Instant.now().toString();
    link.setStatus("revoked");
    link.setErrorMessage("扫码链接已手动失效。");
    redactTerminalAudit(link);
    link.setCompletedAt(timestamp);
    link.setUpdatedAt(timestamp);
    linkMapper.update(link);
    return publicLink(linkMapper.findByToken(link.getToken()), origin);
  }

  /**
   * 管理员重试失败的老用户重新绑定清理任务。
   */
  public PublicWechatBindLink retryCleanup(String token, String origin) {
    WechatBindLinkEntity link = requireLink(token);
    if (!"cleanup_failed".equals(link.getStatus())) {
      throw new ApiException(HttpStatus.CONFLICT, "当前重新绑定任务不需要重试清理。");
    }
    WechatBindLinkEntity result = rebindService.retry(link.getToken());
    publishLink(result, origin);
    return publicLink(result, origin);
  }

  /**
   * 管理员取消仍处于可逆阶段的失败清理任务。
   */
  public PublicWechatBindLink cancelCleanup(String token, String origin) {
    WechatBindLinkEntity link = requireLink(token);
    if (!"cleanup_failed".equals(link.getStatus())) {
      throw new ApiException(HttpStatus.CONFLICT, "当前重新绑定任务不能取消清理。");
    }
    WechatBindLinkEntity result = rebindService.cancelFailed(link.getToken());
    publishLink(result, origin);
    return publicLink(result, origin);
  }

  /**
   * 读取公开绑定链接状态；老用户链接首次访问时自动启动出码。
   */
  @Transactional
  public PublicWechatBindLink getPublicStatus(String token, String origin) {
    WechatBindLinkEntity link = expireIfNeeded(requireLink(token));
    if (hasLinkTtlExpired(link)) {
      return publicLink(link, origin);
    }
    if ("created".equals(link.getStatus())) {
      scheduleQrAfterCommit(link.getToken(), false, origin);
    }
    link = reconcile(link);
    return publicLink(link, origin);
  }

  /**
   * 新用户提交手机号后分配实例并生成二维码。
   */
  @Transactional
  public PublicWechatBindLink submitPhone(String token, String phone, String origin) {
    throw new ApiException(HttpStatus.BAD_REQUEST, "手机号由管理员创建扫码链接时填写。");
  }

  /**
   * 二维码过期或失败后重新生成二维码。
   */
  @Transactional
  public PublicWechatBindLink refreshQr(String token, String origin) {
    WechatBindLinkEntity link = expireIfNeeded(requireLink(token));
    if (hasLinkTtlExpired(link)) {
      return publicLink(link, origin);
    }
    if (isTerminal(link.getStatus())) {
      return publicLink(link, origin);
    }
    link = markQrStarting(link);
    publishLink(link, origin);
    scheduleQrAfterCommit(link.getToken(), true, origin);
    return publicLink(link, origin);
  }

  private void scheduleQrAfterCommit(String token, boolean force, String origin) {
    Runnable task = () -> startQrAsync(token, force, origin);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          task.run();
        }
      });
      return;
    }
    task.run();
  }

  private void startQrAsync(String token, boolean force, String origin) {
    if (qrJobs.putIfAbsent(token, true) != null) {
      return;
    }
    executor.execute(() -> {
      try {
        WechatBindLinkEntity link = linkMapper.findByToken(token);
        if (link == null || isTerminal(link.getStatus())) {
          return;
        }
        if (hasLinkTtlExpired(link)) {
          markExpired(link);
          publishLink(linkMapper.findByToken(token), origin);
          return;
        }
        startQr(link, force, origin);
      } finally {
        qrJobs.remove(token);
      }
    });
  }

  private WechatBindLinkEntity markQrStarting(WechatBindLinkEntity link) {
    requireBindableInstance(
        link.getInstanceId(),
        "当前 OpenClaw 实例暂不可用，请稍后再试。"
    );
    String targetAccountId = targetAccountId(link);
    String now = Instant.now().toString();
    link.setTargetAccountId(targetAccountId);
    link.setStartedAt(now);
    link.setStatus("starting");
    link.setQrMode(null);
    link.setQrPayload("");
    link.setQrLink("");
    link.setQrExpiresAt(null);
    link.setErrorMessage(null);
    link.setUpdatedAt(now);
    linkMapper.update(link);
    return link;
  }

  private WechatBindLinkEntity startQr(WechatBindLinkEntity link, boolean force, String origin) {
    InstanceEntity instance = requireBindableInstance(
        link.getInstanceId(),
        "当前 OpenClaw 实例暂不可用，请稍后再试。"
    );
    link = markQrStarting(link);
    publishLink(link, origin);

    try {
      log.info("开始生成微信扫码二维码：mode={}, instanceId={}, forceRegenerate={}", link.getMode(), instance.getId(), force);
      String token = link.getToken();
      WechatBindService.BindStartResult started = wechatBindService.startBind(
          instance,
          force,
          link.getTargetAccountId(),
          completion -> completeBindAfterLogin(token, completion, origin)
      );
      WechatBindLinkEntity latest = linkMapper.findByToken(link.getToken());
      if (latest != null && isTerminal(latest.getStatus())) {
        publishLink(latest, origin);
        return latest;
      }
      if (latest != null) {
        link = latest;
      }
      link.setTargetAccountId(started.accountId());
      link.setStatus("waiting_scan");
      link.setQrMode(started.qrMode());
      link.setQrPayload(defaultString(started.qrPayload()));
      link.setQrLink(defaultString(started.qrLink()));
      link.setQrExpiresAt(qrExpiresAt());
      link.setErrorMessage(null);
      link.setUpdatedAt(Instant.now().toString());
      linkMapper.update(link);
      publishLink(link, origin);
    } catch (ApiException error) {
      cleanupTemporaryAccountState(link);
      markFailed(link, error.getMessage());
    } catch (RuntimeException error) {
      cleanupTemporaryAccountState(link);
      markFailed(link, error.getMessage() == null ? "生成二维码失败。" : error.getMessage());
    }
    WechatBindLinkEntity latest = linkMapper.findByToken(link.getToken());
    WechatBindLinkEntity result = latest == null ? link : latest;
    publishLink(result, origin);
    return result;
  }

  void completeBindAfterLogin(String token, WechatBindService.BindCompletion completion, String origin) {
    WechatBindLinkEntity link = linkMapper.findByToken(token);
    if (link == null || completion == null || isTerminal(link.getStatus())) {
      return;
    }
    String expectedAccountId = defaultString(link.getTargetAccountId()).trim();
    if (!expectedAccountId.equals(defaultString(completion.requestedAccountId()).trim())) {
      log.info(
          "忽略非本链接微信登录完成事件：tokenPresent={}, expectedAccountHash={}, requestedAccountHash={}, completedAccountHash={}",
          WechatLogSanitizer.present(token),
          WechatLogSanitizer.identityHashPreview(expectedAccountId),
          WechatLogSanitizer.identityHashPreview(completion.requestedAccountId()),
          WechatLogSanitizer.identityHashPreview(completion.accountId())
      );
      return;
    }
    if (!hasText(link.getInstanceId())) {
      return;
    }
    InstanceEntity instance = aggregateMapper.findById(link.getInstanceId());
    if (instance == null) {
      cleanupRejectedNewLogin(link.getInstanceId(), link, completion.accountId(), "");
      markRejected(link, "OpenClaw 实例已不存在，已清理本次扫码临时账号。");
      publishLink(linkMapper.findByToken(link.getToken()), origin);
      return;
    }
    link = markScanned(link, completion);
    publishLink(link, origin);
    WechatBindLinkEntity completed = finalizeConnected(link, instance, completion, origin);
    publishLink(completed, origin);
  }

  private WechatBindLinkEntity reconcile(WechatBindLinkEntity link) {
    if (!hasText(link.getInstanceId()) || isTerminal(link.getStatus())) {
      return link;
    }

    if ("waiting_scan".equals(link.getStatus()) && isExpired(link.getQrExpiresAt())) {
      markExpired(link, "二维码已过期，请重新生成后扫码绑定。", true);
      return link;
    }

    return link;
  }

  private WechatBindLinkEntity finalizeConnected(
      WechatBindLinkEntity link,
      InstanceEntity instance,
      WechatBindService.BindCompletion completion,
      String origin
  ) {
    if ("existing".equals(link.getMode())) {
      return finalizeExisting(link, instance, completion, origin);
    }
    return finalizeNew(link, instance, completion, origin);
  }

  private WechatBindLinkEntity finalizeNew(
      WechatBindLinkEntity link,
      InstanceEntity instance,
      WechatBindService.BindCompletion completion,
      String origin
  ) {
    String accountId = defaultString(completion.accountId()).trim();
    String wechatUserId = defaultString(completion.wechatUserId()).trim();
    if (accountId.isBlank() || wechatUserId.isBlank()) {
      cleanupRejectedNewLogin(instance, link, accountId, "");
      markRejected(link, "无法识别扫码微信用户，请重新扫码或联系管理员处理。");
      return linkMapper.findByToken(link.getToken());
    }
    link.setScannedWechatUserId(wechatUserId);

    WechatPairedAccountEntity existingByAccount = aggregateMapper.findWechatAccountByAccountId(accountId);
    if (existingByAccount != null) {
      refreshOriginalAccountCredential(instance, accountId, existingByAccount);
      cleanupRejectedNewLogin(instance, link, accountId, protectedAccountId(instance, existingByAccount));
      restartExistingWechatChannel(existingByAccount);
      markRejected(link, "该微信已绑定到其他手机号或实例，请联系管理员处理。");
      return linkMapper.findByToken(link.getToken());
    }

    WechatPairedAccountEntity existingByWechatUser = aggregateMapper.findWechatAccountByWechatUserId(wechatUserId);
    if (existingByWechatUser != null) {
      refreshOriginalAccountCredential(instance, accountId, existingByWechatUser);
      cleanupRejectedNewLogin(instance, link, accountId, protectedAccountId(instance, existingByWechatUser));
      restartExistingWechatChannel(existingByWechatUser);
      markRejected(link, "该微信已绑定到其他手机号或实例，请联系管理员处理。");
      return linkMapper.findByToken(link.getToken());
    }

    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    String now = Instant.now().toString();
    account.setAccountId(accountId);
    account.setPhone(link.getPhone());
    account.setInstanceId(instance.getId());
    account.setWechatUserId(wechatUserId);
    account.setRemark("");
    account.setBaseUrl(defaultString(completion.baseUrl()));
    account.setSavedAt(now);
    account.setBoundAt(now);
    account.setUpdatedAt(now);

    try {
      mutationMapper.insertWechatAccount(account);
    } catch (DuplicateKeyException error) {
      cleanupRejectedNewLogin(instance, link, accountId, "");
      markRejected(link, "该手机号或微信账号已完成绑定，请联系管理员处理。");
      return linkMapper.findByToken(link.getToken());
    }

    link = markInitializing(link, accountId, wechatUserId, "");
    publishLink(link, origin);
    accountSyncService.syncInstanceAccounts(instance);
    markChannelStarting(account, "微信扫码绑定成功，正在启动微信通道。");
    if (startWechatChannel(instance, accountId)) {
      markChannelReady(account, "微信扫码绑定成功，微信通道已激活。");
    } else {
      markChannelError(account, "微信扫码绑定成功，但通道热启动失败。", "");
    }
    try {
      publishConnectedProvisioning(link, accountId);
    } catch (RuntimeException error) {
      stopWechatChannel(instance, accountId);
      markFailed(link, "用户 Agent 初始化失败，请重试初始化。");
      return linkMapper.findByToken(link.getToken());
    }
    markConnected(link);
    log.info("新用户微信绑定完成：instanceId={}", instance.getId());
    return linkMapper.findByToken(link.getToken());
  }

  private WechatBindLinkEntity finalizeExisting(
      WechatBindLinkEntity link,
      InstanceEntity instance,
      WechatBindService.BindCompletion completion,
      String origin
  ) {
    String expectedAccountId = targetAccountId(link);
    String actualAccountId = defaultString(completion.accountId()).trim();
    WechatPairedAccountEntity pairedAccount = aggregateMapper.findWechatAccountByAccountId(expectedAccountId);
    if (pairedAccount == null) {
      cleanupRejectedNewLogin(instance, link, actualAccountId, expectedAccountId);
      markRejected(link, "该手机号历史绑定的微信账号不存在，请联系管理员处理。");
      return linkMapper.findByToken(link.getToken());
    }
    String scannedWechatUserId = defaultString(completion.wechatUserId()).trim();
    if (actualAccountId.isBlank() || scannedWechatUserId.isBlank()) {
      cleanupRejectedNewLogin(instance, link, actualAccountId, expectedAccountId);
      markRejected(link, "无法识别扫码微信用户，请重新扫码或联系管理员处理。");
      return linkMapper.findByToken(link.getToken());
    }
    link.setScannedWechatUserId(scannedWechatUserId);
    if (!scannedWechatUserId.equals(defaultString(pairedAccount.getWechatUserId()).trim())) {
      cleanupRejectedNewLogin(instance, link, actualAccountId, expectedAccountId);
      markRejected(link, "扫码微信与该手机号历史绑定的微信不一致，已拒绝本次绑定。");
      return linkMapper.findByToken(link.getToken());
    }

    try {
      WechatBindLinkEntity result = rebindService.startOrResume(
          link,
          pairedAccount,
          instance,
          actualAccountId,
          scannedWechatUserId
      );
      publishLink(result, origin);
      return result;
    } catch (RuntimeException error) {
      // startOrResume only throws before it can persist a recoverable operation.
      // Keep the old account protected, remove the actual scanned temporary account,
      // and finish this link as a terminal rejection rather than leaving a ghost provider.
      cleanupRejectedNewLogin(instance, link, actualAccountId, expectedAccountId);
      markRejected(link, "老用户重新绑定初始化失败，请重新扫码或联系管理员处理。");
      log.warn(
          "老用户重新绑定初始化失败，已清理扫码临时账号：instanceId={}, accountHash={}, reason={}",
          instance.getId(),
          WechatLogSanitizer.identityHashPreview(actualAccountId),
          error.getClass().getSimpleName()
      );
      WechatBindLinkEntity rejected = linkMapper.findByToken(link.getToken());
      publishLink(rejected, origin);
      return rejected;
    }
  }

  private InstanceEntity chooseBindableInstance() {
    List<InstanceEntity> candidates = bindableInstances();
    if (candidates.isEmpty()) {
      throw new ApiException(HttpStatus.CONFLICT, "当前暂无可用 OpenClaw 实例，请先启动并等待实例就绪。");
    }

    int minBoundAccounts = candidates.stream()
        .map(instance -> aggregateMapper.countWechatAccountsByInstanceId(instance.getId()))
        .min(Comparator.naturalOrder())
        .orElse(0);
    List<InstanceEntity> leastLoaded = candidates.stream()
        .filter(instance -> aggregateMapper.countWechatAccountsByInstanceId(instance.getId()) == minBoundAccounts)
        .toList();
    return leastLoaded.get(ThreadLocalRandom.current().nextInt(leastLoaded.size()));
  }

  private void restartExistingWechatChannel(WechatPairedAccountEntity account) {
    try {
      InstanceEntity instance = aggregateMapper.findById(account.getInstanceId());
      if (instance == null) {
        return;
      }
      markChannelStarting(account, "检测到重复微信扫码，正在重新激活原账号通道。");
      gatewayRpcService.restartWechatChannel(instance, List.of(account.getAccountId()));
      markChannelReady(account, "原账号微信通道已重新激活。");
    } catch (RuntimeException error) {
      markChannelError(account, "原账号微信通道重新激活失败。", error.getMessage());
      log.warn(
          "重复微信拒绝后热启动原账号失败：instanceId={}, accountHash={}, reason={}",
          defaultString(account.getInstanceId()),
          WechatLogSanitizer.identityHashPreview(account.getAccountId()),
          error.getMessage()
      );
    }
  }

  private void refreshOriginalAccountCredential(
      InstanceEntity sourceInstance,
      String sourceAccountId,
      WechatPairedAccountEntity existingAccount
  ) {
    try {
      accountSyncService.refreshAccountCredentialsFromRejectedLogin(sourceInstance, sourceAccountId, existingAccount);
    } catch (RuntimeException error) {
      log.warn(
          "重复微信拒绝前刷新原账号凭证失败：sourceInstanceId={}, sourceAccountHash={}, targetInstanceId={}, targetAccountHash={}, reason={}",
          sourceInstance == null ? "" : sourceInstance.getId(),
          WechatLogSanitizer.identityHashPreview(sourceAccountId),
          existingAccount == null ? "" : defaultString(existingAccount.getInstanceId()),
          WechatLogSanitizer.identityHashPreview(existingAccount == null ? "" : existingAccount.getAccountId()),
          error.getMessage()
      );
    }
  }

  private boolean startWechatChannel(InstanceEntity instance, String accountId) {
    try {
      gatewayRpcService.restartWechatChannel(instance, List.of(accountId));
      return true;
    } catch (RuntimeException error) {
      log.warn(
          "微信通道热启动失败：instanceId={}, accountHash={}, reason={}",
          instance.getId(),
          WechatLogSanitizer.identityHashPreview(accountId),
          error.getMessage()
      );
      return false;
    }
  }

  private void stopWechatChannel(InstanceEntity instance, String accountId) {
    try {
      gatewayRpcService.stopWechatChannel(instance, List.of(accountId));
    } catch (RuntimeException error) {
      log.warn(
          "Agent 初始化失败后停止微信通道失败：instanceId={}, accountHash={}, reason={}",
          instance.getId(),
          WechatLogSanitizer.identityHashPreview(accountId),
          error.getMessage()
      );
    }
  }

  private void requireAnyBindableInstance() {
    if (bindableInstances().isEmpty()) {
      throw new ApiException(HttpStatus.CONFLICT, "当前暂无可用 OpenClaw 实例，请先启动并等待实例就绪。");
    }
  }

  private InstanceEntity requireBindableInstance(String instanceId, String message) {
    InstanceEntity instance = requireInstance(instanceId);
    if (!"running".equals(instance.getStatus()) || !isProvisioningReady(instance.getId())) {
      throw new ApiException(HttpStatus.CONFLICT, message);
    }
    return instance;
  }

  private List<InstanceEntity> bindableInstances() {
    List<InstanceEntity> instances = aggregateMapper.listAll();
    if (instances.isEmpty()) {
      return List.of();
    }
    List<String> instanceIds = instances.stream().map(InstanceEntity::getId).toList();
    Map<String, InstanceProvisioningEntity> provisioningByInstance = aggregateMapper.listProvisioningByInstanceIds(instanceIds)
        .stream()
        .collect(Collectors.toMap(InstanceProvisioningEntity::getInstanceId, item -> item));
    return instances.stream()
        .filter(instance -> "running".equals(instance.getStatus()))
        .filter(instance -> {
          InstanceProvisioningEntity provisioning = provisioningByInstance.get(instance.getId());
          return provisioning != null && "ready".equals(provisioning.getStatus());
        })
        .toList();
  }

  private boolean isProvisioningReady(String instanceId) {
    return aggregateMapper.listProvisioningByInstanceIds(List.of(instanceId)).stream()
        .anyMatch(provisioning -> "ready".equals(provisioning.getStatus()));
  }

  private void cleanupTemporaryAccountState(WechatBindLinkEntity link) {
    String instanceId = defaultString(link == null ? null : link.getInstanceId()).trim();
    if (instanceId.isBlank()) {
      return;
    }
    InstanceEntity instance = aggregateMapper.findById(instanceId);
    if (instance == null) {
      return;
    }
    try {
      cleanupRejectedNewLogin(instance, link, "", "");
    } catch (RuntimeException error) {
      log.warn(
          "清理扫码临时微信账号失败：instanceId={}, accountHash={}, reason={}",
          instanceId,
          WechatLogSanitizer.identityHashPreview(link.getTargetAccountId()),
          defaultString(error.getMessage())
      );
    }
  }

  private void cleanupRejectedNewLogin(
      InstanceEntity instance,
      WechatBindLinkEntity link,
      String actualAccountId,
      String protectedAccountId
  ) {
    cleanupRejectedNewLogin(instance.getId(), link, actualAccountId, protectedAccountId);
  }

  private void cleanupRejectedNewLogin(
      String instanceId,
      WechatBindLinkEntity link,
      String actualAccountId,
      String protectedAccountId
  ) {
    String targetAccountId = defaultString(link.getTargetAccountId()).trim();
    cleanupAccountStateIfUnbound(instanceId, targetAccountId, protectedAccountId);
    String normalizedActualAccountId = defaultString(actualAccountId).trim();
    if (normalizedActualAccountId.isBlank() || normalizedActualAccountId.equals(targetAccountId)) {
      return;
    }
    cleanupAccountStateIfUnbound(instanceId, normalizedActualAccountId, protectedAccountId);
  }

  private String protectedAccountId(InstanceEntity currentInstance, WechatPairedAccountEntity existingAccount) {
    if (existingAccount == null || currentInstance == null) {
      return "";
    }
    return currentInstance.getId().equals(existingAccount.getInstanceId())
        ? defaultString(existingAccount.getAccountId()).trim()
        : "";
  }

  private void cleanupAccountStateIfUnbound(
      InstanceEntity instance,
      String accountId,
      String protectedAccountId
  ) {
    cleanupAccountStateIfUnbound(instance.getId(), accountId, protectedAccountId);
  }

  private void cleanupAccountStateIfUnbound(
      String instanceId,
      String accountId,
      String protectedAccountId
  ) {
    String normalizedAccountId = defaultString(accountId).trim();
    if (normalizedAccountId.isBlank() || normalizedAccountId.equals(defaultString(protectedAccountId).trim())) {
      return;
    }
    if (aggregateMapper.findWechatAccountByAccountId(normalizedAccountId) != null) {
      return;
    }
    accountSyncService.removeAccountStateFiles(fileService.paths(instanceId), normalizedAccountId);
  }

  private void markChannelStarting(WechatPairedAccountEntity account, String message) {
    upsertChannel(account, "starting", message, "", Instant.now().toString(), null);
  }

  private void markChannelReady(WechatPairedAccountEntity account, String message) {
    upsertChannel(account, "ready", message, "", Instant.now().toString(), null);
  }

  private void markChannelError(WechatPairedAccountEntity account, String message, String outputSnippet) {
    upsertChannel(account, "error", message, outputSnippet, null, Instant.now().toString());
  }

  private void upsertChannel(
      WechatPairedAccountEntity account,
      String status,
      String message,
      String outputSnippet,
      String lastStartedAt,
      String lastErrorAt
  ) {
    if (account == null || defaultString(account.getWechatUserId()).trim().isBlank()) {
      return;
    }
    WechatAccountChannelEntity channel = new WechatAccountChannelEntity();
    channel.setAccountId(account.getAccountId());
    channel.setInstanceId(account.getInstanceId());
    channel.setWechatUserId(account.getWechatUserId());
    channel.setStatus(status);
    channel.setMessage(defaultString(message));
    channel.setOutputSnippet(defaultString(outputSnippet));
    channel.setLastStartedAt(lastStartedAt);
    channel.setLastErrorAt(lastErrorAt);
    channel.setUpdatedAt(Instant.now().toString());
    mutationMapper.upsertWechatAccountChannel(channel);
  }

  private void clearQr(WechatBindLinkEntity link) {
    link.setQrMode(null);
    link.setQrPayload("");
    link.setQrLink("");
    link.setQrExpiresAt(null);
  }

  private void redactTerminalAudit(WechatBindLinkEntity link) {
    redactTerminalAudit(link, false);
  }

  private void redactTerminalAudit(WechatBindLinkEntity link, boolean preserveMiniappAssociation) {
    link.setQrMode(null);
    link.setQrPayload(null);
    link.setQrLink(null);
    link.setQrExpiresAt(null);
    link.setScannedWechatUserId(null);
    link.setTargetAccountId(null);
    if (!preserveMiniappAssociation) {
      link.setMiniappOpenidHash(null);
    }
    link.setCleanupError(null);
  }

  private WechatBindLinkEntity markScanned(WechatBindLinkEntity link, WechatBindService.BindCompletion completion) {
    clearQr(link);
    String wechatUserId = defaultString(completion.wechatUserId()).trim();
    if (!wechatUserId.isBlank()) {
      link.setScannedWechatUserId(wechatUserId);
    }
    link.setStatus("scanned");
    link.setErrorMessage(null);
    link.setUpdatedAt(Instant.now().toString());
    linkMapper.update(link);
    logStatusChange(link, "scanned", defaultString(completion.accountId()).trim(), wechatUserId, "");
    return link;
  }

  private WechatBindLinkEntity markInitializing(
      WechatBindLinkEntity link,
      String accountId,
      String wechatUserId,
      String openVikingUserId
  ) {
    clearQr(link);
    link.setTargetAccountId(defaultString(accountId).trim());
    link.setScannedWechatUserId(defaultString(wechatUserId).trim());
    link.setStatus("initializing");
    link.setErrorMessage(null);
    link.setUpdatedAt(Instant.now().toString());
    linkMapper.update(link);
    logStatusChange(link, "initializing", accountId, wechatUserId, openVikingUserId);
    return link;
  }

  private void markConnected(WechatBindLinkEntity link) {
    String accountId = defaultString(link.getTargetAccountId()).trim();
    String wechatUserId = defaultString(link.getScannedWechatUserId()).trim();
    link.setStatus("connected");
    link.setErrorMessage(null);
    String now = Instant.now().toString();
    redactTerminalAudit(link);
    link.setCompletedAt(now);
    link.setUpdatedAt(now);
    linkMapper.update(link);
    logStatusChange(link, "connected", accountId, wechatUserId, "");
  }

  private void markRejected(WechatBindLinkEntity link, String message) {
    String accountId = defaultString(link.getTargetAccountId()).trim();
    String wechatUserId = defaultString(link.getScannedWechatUserId()).trim();
    String timestamp = Instant.now().toString();
    link.setStatus("rejected");
    link.setErrorMessage(message);
    redactTerminalAudit(link);
    link.setCompletedAt(timestamp);
    link.setUpdatedAt(timestamp);
    linkMapper.update(link);
    logStatusChange(link, "rejected", accountId, wechatUserId, "");
    log.warn("微信扫码链接已拒绝：mode={}, instanceId={}, reason={}", link.getMode(), defaultString(link.getInstanceId()), message);
  }

  private void markFailed(WechatBindLinkEntity link, String message) {
    clearQr(link);
    link.setStatus("failed");
    link.setErrorMessage(defaultString(message).isBlank() ? "二维码生成失败，请稍后重试。" : message);
    link.setUpdatedAt(Instant.now().toString());
    linkMapper.update(link);
    logStatusChange(
        link,
        "failed",
        defaultString(link.getTargetAccountId()).trim(),
        defaultString(link.getScannedWechatUserId()).trim(),
        ""
    );
    log.warn("微信扫码链接失败：mode={}, instanceId={}, reason={}", link.getMode(), defaultString(link.getInstanceId()), link.getErrorMessage());
  }

  private void markExpired(WechatBindLinkEntity link) {
    markExpired(link, "扫码链接已过期，请联系管理员重新生成。");
  }

  private void markExpired(WechatBindLinkEntity link, String message) {
    markExpired(link, message, false);
  }

  private void markExpired(WechatBindLinkEntity link, String message, boolean preserveMiniappAssociation) {
    String accountId = defaultString(link.getTargetAccountId()).trim();
    String wechatUserId = defaultString(link.getScannedWechatUserId()).trim();
    cleanupTemporaryAccountState(link);
    String timestamp = Instant.now().toString();
    link.setStatus("expired");
    link.setErrorMessage(message);
    redactTerminalAudit(link, preserveMiniappAssociation);
    link.setCompletedAt(timestamp);
    link.setUpdatedAt(timestamp);
    linkMapper.update(link);
    logStatusChange(link, "expired", accountId, wechatUserId, "");
  }

  private boolean isReusableMiniappLink(WechatBindLinkEntity link) {
    return link != null
        && !("waiting_scan".equals(link.getStatus()) && isExpired(link.getQrExpiresAt()));
  }

  private void logStatusChange(
      WechatBindLinkEntity link,
      String status,
      String accountId,
      String wechatUserId,
      String openVikingUserId
  ) {
    log.info(
        "微信扫码链接状态更新：bindTokenPresent={}, status={}, instanceId={}, targetAccountHash={}, wechatUserHash={}, openVikingUserHash={}, elapsedMs={}",
        WechatLogSanitizer.present(link.getToken()),
        status,
        defaultString(link.getInstanceId()),
        WechatLogSanitizer.identityHashPreview(accountId),
        WechatLogSanitizer.identityHashPreview(wechatUserId),
        WechatLogSanitizer.identityHashPreview(openVikingUserId),
        elapsedSinceStarted(link)
    );
  }

  private void publishConnectedProvisioning(WechatBindLinkEntity link, String accountId) {
    String instanceId = defaultString(link.getInstanceId()).trim();
    String normalizedAccountId = defaultString(accountId).trim();
    String wechatUserId = defaultString(link.getScannedWechatUserId()).trim();
    if (instanceId.isBlank() || normalizedAccountId.isBlank() || wechatUserId.isBlank()) {
      throw new ApiException(HttpStatus.CONFLICT, "扫码身份信息不完整，无法初始化用户 Agent。");
    }
    WechatBindConnectedEvent event = new WechatBindConnectedEvent(
        instanceId,
        normalizedAccountId,
        wechatUserId,
        defaultString(link.getMiniappOpenidHash()).trim()
    );
    bindEventPublisher.publishEvent(event);
  }

  @Transactional
  public PublicWechatBindLink retryAgentProvisioning(String token, String origin) {
    WechatBindLinkEntity link = requireLink(token);
    if (!"failed".equals(link.getStatus())) {
      throw new ApiException(HttpStatus.CONFLICT, "当前扫码链接不需要重试初始化。");
    }
    String accountId = defaultString(link.getTargetAccountId()).trim();
    String wechatUserId = defaultString(link.getScannedWechatUserId()).trim();
    if (accountId.isBlank() || wechatUserId.isBlank() || defaultString(link.getInstanceId()).trim().isBlank()) {
      throw new ApiException(HttpStatus.CONFLICT, "扫码身份信息不完整，请重新扫码。");
    }
    InstanceEntity instance = requireInstance(link.getInstanceId());
    link = markInitializing(link, accountId, wechatUserId, "");
    publishLink(link, origin);
    try {
      publishConnectedProvisioning(link, accountId);
      if (!startWechatChannel(instance, accountId)) {
        stopWechatChannel(instance, accountId);
        markFailed(link, "用户 Agent 已初始化，但微信通道启动失败，请稍后重试。");
      } else {
        markConnected(link);
      }
    } catch (RuntimeException error) {
      stopWechatChannel(instance, accountId);
      markFailed(link, "用户 Agent 初始化失败，请稍后重试。");
    }
    PublicWechatBindLink result = publicLink(linkMapper.findByToken(token), origin);
    publishLink(linkMapper.findByToken(token), origin);
    return result;
  }

  private long elapsedSinceStarted(WechatBindLinkEntity link) {
    String startedAt = defaultString(link.getStartedAt()).trim();
    if (startedAt.isBlank()) {
      return -1;
    }
    try {
      return Math.max(0, Duration.between(Instant.parse(startedAt), Instant.now()).toMillis());
    } catch (DateTimeParseException error) {
      return -1;
    }
  }

  private WechatBindLinkEntity requireLink(String token) {
    String normalized = token == null ? "" : token.trim();
    WechatBindLinkEntity link = linkMapper.findByToken(normalized);
    if (link == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "扫码链接不存在。");
    }
    return link;
  }

  private WechatBindLinkEntity expireIfNeeded(WechatBindLinkEntity link) {
    if (!isLinkExpired(link)) {
      return link;
    }
    markExpired(link);
    return linkMapper.findByToken(link.getToken());
  }

  private InstanceEntity requireInstance(String instanceId) {
    InstanceEntity instance = aggregateMapper.findById(instanceId);
    if (instance == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "OpenClaw 实例不存在。");
    }
    return instance;
  }

  private WechatPairedAccountEntity requireBindingByPhone(String phone) {
    WechatPairedAccountEntity account = aggregateMapper.findWechatAccountByPhone(phone);
    if (account == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "该手机号尚未绑定微信账号。");
    }
    return account;
  }

  private PublicWechatBindLink publicLink(WechatBindLinkEntity link, String origin) {
    InstanceEntity instance = hasText(link.getInstanceId()) ? aggregateMapper.findById(link.getInstanceId()) : null;
    InstanceProvisioningEntity provisioning = instance == null
        ? null
        : aggregateMapper.listProvisioningByInstanceIds(List.of(instance.getId())).stream().findFirst().orElse(null);
    boolean linkExpired = hasLinkTtlExpired(link) && !isTerminal(link.getStatus()) && !isCleanupStatus(link.getStatus());
    boolean qrExpired = isExpired(link.getQrExpiresAt());
    boolean hiddenQr = linkExpired || (qrExpired && "waiting_scan".equals(link.getStatus()));
    String status = effectiveStatus(link);
    return new PublicWechatBindLink(
        link.getToken(),
        defaultString(link.getMode()),
        status,
        defaultString(link.getPhone()),
        defaultString(link.getInstanceId()),
        instance == null ? "" : defaultString(instance.getName()),
        hiddenQr ? null : link.getQrMode(),
        hiddenQr ? "" : defaultString(link.getQrPayload()),
        hiddenQr ? "" : defaultString(link.getQrLink()),
        link.getQrExpiresAt(),
        linkExpired || qrExpired,
        message(link, status, provisioning),
        defaultString(link.getCleanupStage()),
        "cleanup_failed".equals(status),
        sanitizeCleanupError(link.getCleanupError()),
        link.getExpiresAt(),
        link.getCompletedAt(),
        link.getCreatedAt(),
        link.getUpdatedAt(),
        statusLabel(status),
        modeLabel(link.getMode()),
        bindLink(origin, link.getToken())
    );
  }

  private String sanitizeCleanupError(String value) {
    String normalized = defaultString(value)
        .replaceAll("(?i)(token|key|secret|password)\\s*[=:]\\s*\\S+", "$1=[redacted]");
    return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
  }

  private String effectiveStatus(WechatBindLinkEntity link) {
    if (hasLinkTtlExpired(link) && !isTerminal(link.getStatus()) && !isCleanupStatus(link.getStatus())) {
      return "expired";
    }
    if (isExpired(link.getQrExpiresAt()) && "waiting_scan".equals(link.getStatus())) {
      return "expired";
    }
    return defaultString(link.getStatus());
  }

  private String message(WechatBindLinkEntity link, String status, InstanceProvisioningEntity provisioning) {
    if (hasText(link.getErrorMessage())) {
      return link.getErrorMessage();
    }
    return switch (status) {
      case "phone_required" -> "请先填写手机号获取微信扫码二维码。";
      case "created", "starting" -> "正在准备微信扫码二维码，请稍候。";
      case "waiting_scan" -> "请使用微信扫描二维码完成绑定。";
      case "scanned" -> "已扫码，正在确认登录结果。";
      case "initializing" -> "身份已确认，正在初始化微信通道。";
      case "cleaning" -> "已确认原微信用户，正在替换旧身份并清理历史 Agent 数据。";
      case "cleanup_failed" -> "重新绑定清理失败，请联系管理员重试。";
      case "connected" -> connectedMessage(provisioning);
      case "expired" -> "二维码已过期，请重新生成后扫码绑定。";
      case "rejected" -> "本次绑定已拒绝，请联系管理员。";
      case "failed" -> "二维码生成失败，请稍后重试。";
      case "revoked" -> "扫码链接已失效，请联系管理员重新生成。";
      default -> "";
    };
  }

  private String connectedMessage(InstanceProvisioningEntity provisioning) {
    if (provisioning != null && "error".equals(provisioning.getStatus())) {
      return "微信已绑定，但 OpenClaw 实例当前异常，请联系管理员处理。";
    }
    if (provisioning != null && !"ready".equals(provisioning.getStatus())) {
      return "微信已绑定成功，OpenClaw 实例正在准备中，请稍后再使用。";
    }
    return "微信绑定成功，可以使用微信连接 OpenClaw。";
  }

  private String bindLink(String origin, String token) {
    String base = origin == null || origin.isBlank() ? "" : origin.replaceAll("/+$", "");
    return base + "/bind/" + token;
  }

  private void publishLink(WechatBindLinkEntity link, String origin) {
    eventPublisher.publishWechatBindLinkUpdated(link.getToken(), publicLink(link, origin));
  }

  private String targetAccountId(WechatBindLinkEntity link) {
    String current = defaultString(link.getTargetAccountId()).trim();
    if (!current.isBlank()) {
      return current;
    }
    if ("existing".equals(link.getMode())) {
      String phone = defaultString(link.getPhone()).trim();
      WechatPairedAccountEntity account = phone.isBlank()
          ? null
          : aggregateMapper.findWechatAccountByPhone(phone);
      String restored = account == null ? "" : defaultString(account.getAccountId()).trim();
      if (restored.isBlank()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "老用户扫码链接缺少历史微信账号。");
      }
      return restored;
    }
    return targetAccountIdFromToken(link.getToken());
  }

  private String targetAccountIdFromToken(String token) {
    String normalizedToken = defaultString(token).replaceAll("[^A-Za-z0-9_-]", "");
    String accountId = "cmwx_" + normalizedToken;
    return accountId.length() <= 255 ? accountId : accountId.substring(0, 255);
  }

  private String qrExpiresAt() {
    return Instant.now().plusMillis(Math.max(1, properties.runtime().wechatQrTtlMs())).toString();
  }

  private String normalizeMode(String mode) {
    String normalized = defaultString(mode).trim().toLowerCase(Locale.ROOT);
    if ("new".equals(normalized) || "existing".equals(normalized)) {
      return normalized;
    }
    throw new ApiException(HttpStatus.BAD_REQUEST, "扫码链接类型无效。");
  }

  private String normalizeOptionalMode(String mode) {
    String normalized = defaultString(mode).trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return "";
    }
    if ("new".equals(normalized) || "existing".equals(normalized)) {
      return normalized;
    }
    throw new ApiException(HttpStatus.BAD_REQUEST, "扫码链接类型无效。");
  }

  private String normalizePhone(String phone) {
    String normalized = defaultString(phone).replaceAll("\\s+", "");
    if (!PHONE_PATTERN.matcher(normalized).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "手机号格式无效。");
    }
    return normalized;
  }

  private String normalizePhoneKeyword(String phone) {
    return defaultString(phone).replaceAll("\\s+", "");
  }

  private boolean isExpired(String expiresAt) {
    if (!hasText(expiresAt)) {
      return false;
    }
    try {
      return !Instant.parse(expiresAt).isAfter(Instant.now());
    } catch (DateTimeParseException error) {
      return false;
    }
  }

  private boolean isLinkExpired(WechatBindLinkEntity link) {
    if (link == null || "expired".equals(link.getStatus()) || isTerminal(link.getStatus())
        || isCleanupStatus(link.getStatus())) {
      return false;
    }
    return hasLinkTtlExpired(link);
  }

  private boolean hasLinkTtlExpired(WechatBindLinkEntity link) {
    return link != null && isExpired(link.getExpiresAt());
  }

  private boolean isTerminal(String status) {
    return "connected".equals(status) || "rejected".equals(status) || "revoked".equals(status)
        || "cleanup_failed".equals(status);
  }

  private boolean isCleanupStatus(String status) {
    return "cleaning".equals(status) || "cleanup_failed".equals(status);
  }

  private static String statusLabel(String status) {
    return switch (defaultString(status)) {
      case "phone_required" -> "待填写手机号";
      case "created" -> "已创建";
      case "starting" -> "出码中";
      case "waiting_scan" -> "等待扫码";
      case "scanned" -> "已扫码";
      case "initializing" -> "初始化中";
      case "cleaning" -> "清理迁移中";
      case "cleanup_failed" -> "清理失败";
      case "connected" -> "已连接";
      case "expired" -> "已过期";
      case "rejected" -> "已拒绝";
      case "failed" -> "出码失败";
      case "revoked" -> "已失效";
      default -> defaultString(status);
    };
  }

  private static String modeLabel(String mode) {
    return switch (defaultString(mode)) {
      case "new" -> "新用户";
      case "existing" -> "老用户";
      default -> defaultString(mode);
    };
  }

  private static String randomToken() {
    return "wbl_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static Executor defaultExecutor() {
    return Executors.newCachedThreadPool(task -> {
      Thread thread = new Thread(task, "wechat-bind-link-" + System.nanoTime());
      thread.setDaemon(true);
      return thread;
    });
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  public record CreateBindLinkRequest(String mode, String phone) {}

  public record AdminLinkPage(List<PublicWechatBindLink> links, int total, int page, int pageSize) {}
}
