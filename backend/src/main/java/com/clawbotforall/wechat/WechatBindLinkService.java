package com.clawbotforall.wechat;

import com.clawbotforall.auth.AuthenticatedAdmin;
import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.instance.InstanceWechatBindingEntity;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员扫码链接、新用户手机号绑定和老用户回原实例绑定流程服务。
 */
@Service
public class WechatBindLinkService {

  private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

  private final WechatBindLinkMapper linkMapper;
  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final WechatBindService wechatBindService;
  private final WechatAccountSyncService accountSyncService;
  private final InstanceFileService fileService;
  private final ClawbotProperties properties;
  private final ObjectMapper objectMapper;

  public WechatBindLinkService(
      WechatBindLinkMapper linkMapper,
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      WechatBindService wechatBindService,
      WechatAccountSyncService accountSyncService,
      InstanceFileService fileService,
      ClawbotProperties properties,
      ObjectMapper objectMapper
  ) {
    this.linkMapper = linkMapper;
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.wechatBindService = wechatBindService;
    this.accountSyncService = accountSyncService;
    this.fileService = fileService;
    this.properties = properties;
    this.objectMapper = objectMapper;
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
    link.setStatus("new".equals(mode) ? "phone_required" : "created");
    link.setCreatedByAdminId(admin.id());
    link.setCreatedAt(now);
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
      link.setExpectedAccountId(account.getAccountId());
    } else {
      requireAnyBindableInstance();
    }

    linkMapper.insert(link);
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
   * 读取公开绑定链接状态；老用户链接首次访问时自动启动出码。
   */
  @Transactional
  public PublicWechatBindLink getPublicStatus(String token, String origin) {
    WechatBindLinkEntity link = requireLink(token);
    if ("existing".equals(link.getMode()) && "created".equals(link.getStatus())) {
      link = startQr(link, false);
    }
    link = reconcile(link);
    return publicLink(link, origin);
  }

  /**
   * 新用户提交手机号后分配实例并生成二维码。
   */
  @Transactional
  public PublicWechatBindLink submitPhone(String token, String phone, String origin) {
    WechatBindLinkEntity link = requireLink(token);
    if (!"new".equals(link.getMode())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "该扫码链接不需要填写手机号。");
    }
    if (isTerminal(link.getStatus())) {
      return publicLink(link, origin);
    }

    String normalizedPhone = normalizePhone(phone);
    if (aggregateMapper.findWechatAccountByPhone(normalizedPhone) != null) {
      markRejected(link, "该手机号已绑定，请联系管理员获取老用户扫码链接。");
      return publicLink(link, origin);
    }

    InstanceEntity instance = chooseBindableInstance();
    link.setPhone(normalizedPhone);
    link.setInstanceId(instance.getId());
    link = startQr(link, false);
    return publicLink(link, origin);
  }

  /**
   * 二维码过期或失败后重新生成二维码。
   */
  @Transactional
  public PublicWechatBindLink refreshQr(String token, String origin) {
    WechatBindLinkEntity link = requireLink(token);
    if ("new".equals(link.getMode()) && !hasText(link.getPhone())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请先填写手机号。");
    }
    if (isTerminal(link.getStatus())) {
      return publicLink(link, origin);
    }
    link = startQr(link, true);
    return publicLink(link, origin);
  }

  private WechatBindLinkEntity startQr(WechatBindLinkEntity link, boolean force) {
    InstanceEntity instance = requireBindableInstance(
        link.getInstanceId(),
        "当前 OpenClaw 实例暂不可用，请稍后再试。"
    );
    List<String> snapshot = accountSyncService.readRawAccountIds(instance);
    String now = Instant.now().toString();
    link.setSnapshotAccountIds(writeJson(snapshot));
    link.setStatus("starting");
    link.setQrMode(null);
    link.setQrPayload("");
    link.setQrLink("");
    link.setQrExpiresAt(null);
    link.setErrorMessage(null);
    link.setUpdatedAt(now);
    linkMapper.update(link);

    try {
      wechatBindService.startBind(instance, force);
    } catch (ApiException error) {
      markFailed(link, error.getMessage());
    } catch (RuntimeException error) {
      markFailed(link, error.getMessage() == null ? "生成二维码失败。" : error.getMessage());
    }
    return linkMapper.findByToken(link.getToken());
  }

  private WechatBindLinkEntity reconcile(WechatBindLinkEntity link) {
    if (!hasText(link.getInstanceId()) || isTerminal(link.getStatus())) {
      return link;
    }

    InstanceEntity instance = requireInstance(link.getInstanceId());
    InstanceWechatBindingEntity binding = aggregateMapper.listWechatBindingByInstanceIds(List.of(instance.getId()))
        .stream()
        .findFirst()
        .orElse(null);
    if (binding == null) {
      return link;
    }

    if ("waiting_scan".equals(binding.getStatus()) && isExpired(binding.getQrExpiresAt())) {
      link.setStatus("expired");
      clearQr(link);
      link.setErrorMessage("二维码已过期，请重新生成后扫码绑定。");
      link.setUpdatedAt(Instant.now().toString());
      linkMapper.update(link);
      return link;
    }

    if ("waiting_scan".equals(binding.getStatus())) {
      copyQr(link, binding);
      link.setStatus("waiting_scan");
      link.setErrorMessage(null);
      link.setUpdatedAt(Instant.now().toString());
      linkMapper.update(link);
      return link;
    }

    if ("starting".equals(binding.getStatus()) || "scanned".equals(binding.getStatus())) {
      link.setStatus(binding.getStatus());
      clearQr(link);
      link.setErrorMessage(null);
      link.setUpdatedAt(Instant.now().toString());
      linkMapper.update(link);
      return link;
    }

    if ("connected".equals(binding.getStatus())) {
      return finalizeConnected(link, instance);
    }

    if ("error".equals(binding.getStatus())) {
      markFailed(link, defaultString(binding.getOutputSnippet()).isBlank() ? "微信扫码绑定失败，请重新生成二维码。" : binding.getOutputSnippet());
      return linkMapper.findByToken(link.getToken());
    }

    return link;
  }

  private WechatBindLinkEntity finalizeConnected(WechatBindLinkEntity link, InstanceEntity instance) {
    List<String> snapshot = readSnapshot(link.getSnapshotAccountIds());
    List<String> currentAccountIds = accountSyncService.readRawAccountIds(instance);
    List<String> added = currentAccountIds.stream()
        .filter(accountId -> !snapshot.contains(accountId))
        .toList();

    if ("existing".equals(link.getMode())) {
      return finalizeExisting(link, instance, currentAccountIds, added);
    }
    return finalizeNew(link, instance, added);
  }

  private WechatBindLinkEntity finalizeNew(
      WechatBindLinkEntity link,
      InstanceEntity instance,
      List<String> added
  ) {
    if (added.isEmpty()) {
      return link;
    }

    String accountId = added.getFirst();
    WechatPairedAccountEntity existingByAccount = aggregateMapper.findWechatAccountByAccountId(accountId);
    if (existingByAccount != null) {
      cleanupAddedAccounts(instance, added);
      markRejected(link, "该微信已绑定到其他手机号或实例，请联系管理员处理。");
      return linkMapper.findByToken(link.getToken());
    }

    WechatPairedAccountEntity rawAccount = rawAccount(instance, accountId);
    WechatPairedAccountEntity account = new WechatPairedAccountEntity();
    String now = Instant.now().toString();
    account.setAccountId(accountId);
    account.setPhone(link.getPhone());
    account.setInstanceId(instance.getId());
    account.setWechatUserId(rawAccount == null ? "" : rawAccount.getWechatUserId());
    account.setRemark("");
    account.setBaseUrl(rawAccount == null ? "" : rawAccount.getBaseUrl());
    account.setSavedAt(rawAccount == null ? null : rawAccount.getSavedAt());
    account.setBoundAt(now);
    account.setUpdatedAt(now);

    try {
      mutationMapper.insertWechatAccount(account);
    } catch (DuplicateKeyException error) {
      cleanupAddedAccounts(instance, added);
      markRejected(link, "该手机号或微信账号已完成绑定，请联系管理员处理。");
      return linkMapper.findByToken(link.getToken());
    }

    accountSyncService.syncInstanceAccounts(instance);
    markConnected(link);
    return linkMapper.findByToken(link.getToken());
  }

  private WechatBindLinkEntity finalizeExisting(
      WechatBindLinkEntity link,
      InstanceEntity instance,
      List<String> currentAccountIds,
      List<String> added
  ) {
    String expectedAccountId = defaultString(link.getExpectedAccountId());
    List<String> unexpectedAdded = added.stream()
        .filter(accountId -> !accountId.equals(expectedAccountId))
        .toList();
    if (!unexpectedAdded.isEmpty()) {
      cleanupAddedAccounts(instance, unexpectedAdded);
      markRejected(link, "扫码微信与该手机号历史绑定的微信不一致，已拒绝本次绑定。");
      return linkMapper.findByToken(link.getToken());
    }

    if (!currentAccountIds.contains(expectedAccountId)) {
      return link;
    }

    accountSyncService.syncInstanceAccounts(instance);
    markConnected(link);
    return linkMapper.findByToken(link.getToken());
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

  private WechatPairedAccountEntity rawAccount(InstanceEntity instance, String accountId) {
    return accountSyncService.readRawAccounts(instance, Map.of()).stream()
        .filter(account -> account.getAccountId().equals(accountId))
        .findFirst()
        .orElse(null);
  }

  private void cleanupAddedAccounts(InstanceEntity instance, List<String> accountIds) {
    for (String accountId : accountIds) {
      accountSyncService.removeAccountStateFiles(fileService.paths(instance.getId()), accountId);
    }
  }

  private void copyQr(WechatBindLinkEntity link, InstanceWechatBindingEntity binding) {
    link.setQrMode(binding.getQrMode());
    link.setQrPayload(defaultString(binding.getQrPayload()));
    link.setQrLink(defaultString(binding.getQrLink()));
    link.setQrExpiresAt(binding.getQrExpiresAt());
  }

  private void clearQr(WechatBindLinkEntity link) {
    link.setQrMode(null);
    link.setQrPayload("");
    link.setQrLink("");
    link.setQrExpiresAt(null);
  }

  private void markConnected(WechatBindLinkEntity link) {
    clearQr(link);
    link.setStatus("connected");
    link.setErrorMessage(null);
    String now = Instant.now().toString();
    link.setCompletedAt(now);
    link.setUpdatedAt(now);
    linkMapper.update(link);
  }

  private void markRejected(WechatBindLinkEntity link, String message) {
    clearQr(link);
    link.setStatus("rejected");
    link.setErrorMessage(message);
    link.setUpdatedAt(Instant.now().toString());
    linkMapper.update(link);
  }

  private void markFailed(WechatBindLinkEntity link, String message) {
    clearQr(link);
    link.setStatus("failed");
    link.setErrorMessage(defaultString(message).isBlank() ? "二维码生成失败，请稍后重试。" : message);
    link.setUpdatedAt(Instant.now().toString());
    linkMapper.update(link);
  }

  private WechatBindLinkEntity requireLink(String token) {
    String normalized = token == null ? "" : token.trim();
    WechatBindLinkEntity link = linkMapper.findByToken(normalized);
    if (link == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "扫码链接不存在。");
    }
    return link;
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
    boolean qrExpired = isExpired(link.getQrExpiresAt());
    String status = qrExpired && "waiting_scan".equals(link.getStatus()) ? "expired" : defaultString(link.getStatus());
    return new PublicWechatBindLink(
        link.getToken(),
        defaultString(link.getMode()),
        status,
        defaultString(link.getPhone()),
        defaultString(link.getInstanceId()),
        instance == null ? "" : defaultString(instance.getName()),
        qrExpired ? null : link.getQrMode(),
        qrExpired ? "" : defaultString(link.getQrPayload()),
        qrExpired ? "" : defaultString(link.getQrLink()),
        link.getQrExpiresAt(),
        qrExpired,
        message(link, status, provisioning),
        bindLink(origin, link.getToken())
    );
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
      case "connected" -> connectedMessage(provisioning);
      case "expired" -> "二维码已过期，请重新生成后扫码绑定。";
      case "rejected" -> "本次绑定已拒绝，请联系管理员。";
      case "failed" -> "二维码生成失败，请稍后重试。";
      default -> "";
    };
  }

  private String connectedMessage(InstanceProvisioningEntity provisioning) {
    if (provisioning != null && "error".equals(provisioning.getStatus())) {
      return "微信已绑定，但 OpenClaw 通道重启失败，请联系管理员处理。";
    }
    if (provisioning != null && !"ready".equals(provisioning.getStatus())) {
      return "微信已绑定成功，OpenClaw 正在重启微信通道，通常需要 1-3 分钟，请稍后再使用。";
    }
    return "微信绑定成功，可以使用微信连接 OpenClaw。";
  }

  private String bindLink(String origin, String token) {
    String base = origin == null || origin.isBlank() ? "" : origin.replaceAll("/+$", "");
    return base + "/bind/" + token;
  }

  private List<String> readSnapshot(String rawJson) {
    if (!hasText(rawJson)) {
      return List.of();
    }
    try {
      List<Object> raw = objectMapper.readValue(rawJson, new TypeReference<>() {});
      LinkedHashSet<String> result = new LinkedHashSet<>();
      for (Object item : raw) {
        String value = defaultString(item == null ? null : String.valueOf(item)).trim();
        if (!value.isBlank()) {
          result.add(value);
        }
      }
      return List.copyOf(result);
    } catch (JsonProcessingException error) {
      return List.of();
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? List.of() : value);
    } catch (JsonProcessingException error) {
      return "[]";
    }
  }

  private String normalizeMode(String mode) {
    String normalized = defaultString(mode).trim().toLowerCase(Locale.ROOT);
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

  private boolean isTerminal(String status) {
    return "connected".equals(status) || "rejected".equals(status);
  }

  private static String randomToken() {
    return "wbl_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  public record CreateBindLinkRequest(String mode, String phone) {}
}
