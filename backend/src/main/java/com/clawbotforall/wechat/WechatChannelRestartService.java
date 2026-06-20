package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceEventPublisher;
import com.clawbotforall.instance.InstanceMutationMapper;
import com.clawbotforall.instance.InstanceQueryService;
import com.clawbotforall.instance.WechatAccountChannelEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 账号粒度重启微信通道，不重启 OpenClaw Gateway 或容器。
 */
@Service
public class WechatChannelRestartService {

  private final InstanceAggregateMapper aggregateMapper;
  private final InstanceMutationMapper mutationMapper;
  private final OpenClawRuntime openClawRuntime;
  private final OpenClawGatewayRpcService gatewayRpcService;
  private final WechatAccountSyncService accountSyncService;
  private final InstanceQueryService queryService;
  private final InstanceEventPublisher eventPublisher;

  public WechatChannelRestartService(
      InstanceAggregateMapper aggregateMapper,
      InstanceMutationMapper mutationMapper,
      OpenClawRuntime openClawRuntime,
      OpenClawGatewayRpcService gatewayRpcService,
      WechatAccountSyncService accountSyncService,
      InstanceQueryService queryService,
      InstanceEventPublisher eventPublisher
  ) {
    this.aggregateMapper = aggregateMapper;
    this.mutationMapper = mutationMapper;
    this.openClawRuntime = openClawRuntime;
    this.gatewayRpcService = gatewayRpcService;
    this.accountSyncService = accountSyncService;
    this.queryService = queryService;
    this.eventPublisher = eventPublisher;
  }

  public RestartWechatChannelResult restartAccount(InstanceEntity instance, String accountId) {
    String normalizedAccountId = normalizeAccountId(accountId);
    WechatPairedAccountEntity account = aggregateMapper.findWechatAccountByAccountId(normalizedAccountId);
    if (account == null || !instance.getId().equals(account.getInstanceId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "微信绑定账号不存在。");
    }
    RuntimeState runtimeState = openClawRuntime.inspectInstance(instance);
    if (!runtimeState.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "请先启动该 OpenClaw 实例，再重启微信通道。");
    }
    markChannel(account, "starting", "正在重启微信通道。", "", Instant.now().toString(), null);
    try {
      gatewayRpcService.restartWechatChannel(instance, List.of(normalizedAccountId));
      accountSyncService.syncInstanceAccounts(instance);
      markChannel(account, "ready", "微信通道已重启。", "", Instant.now().toString(), null);
      publishCurrent(instance.getId());
    } catch (RuntimeException error) {
      markChannel(account, "error", "微信通道重启失败。", error.getMessage(), null, Instant.now().toString());
      publishCurrent(instance.getId());
      throw error;
    }
    return new RestartWechatChannelResult(
        instance.getId(),
        normalizedAccountId,
        "accepted",
        "微信通道已重启。"
    );
  }

  private void publishCurrent(String instanceId) {
    queryService.findPublicInstance(instanceId, null).ifPresent(publicInstance -> {
      eventPublisher.publishWechatBindingUpdated(instanceId, publicInstance.wechatBinding());
      eventPublisher.publishInstanceUpdated(publicInstance);
    });
  }

  private void markChannel(
      WechatPairedAccountEntity account,
      String status,
      String message,
      String outputSnippet,
      String lastStartedAt,
      String lastErrorAt
  ) {
    if (account == null || defaultString(account.getWechatUserId()).isBlank()) {
      return;
    }
    WechatAccountChannelEntity channel = new WechatAccountChannelEntity();
    channel.setAccountId(account.getAccountId());
    channel.setInstanceId(account.getInstanceId());
    channel.setWechatUserId(account.getWechatUserId());
    channel.setStatus(status);
    channel.setMessage(message);
    channel.setOutputSnippet(defaultString(outputSnippet));
    channel.setLastStartedAt(lastStartedAt);
    channel.setLastErrorAt(lastErrorAt);
    channel.setUpdatedAt(Instant.now().toString());
    mutationMapper.upsertWechatAccountChannel(channel);
  }

  private static String normalizeAccountId(String accountId) {
    String normalized = accountId == null ? "" : accountId.trim();
    if (normalized.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "微信账号标识不能为空。");
    }
    return normalized;
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  public record RestartWechatChannelResult(
      String instanceId,
      String accountId,
      String status,
      String message
  ) {}
}
