package com.clawbotforall.wechat;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.WechatAccountChannelEntity;
import com.clawbotforall.instance.WechatPairedAccountEntity;
import com.clawbotforall.miniapp.MiniappUserBindingMapper;
import com.clawbotforall.miniapp.MiniappWechatBindingSummary;
import com.clawbotforall.useragent.UserAgentIdentityEntity;
import com.clawbotforall.useragent.UserAgentIdentityMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 为用户中心合并有效账号与仍需处理的清理任务。 */
@Service
public class WechatUserQueryService {
  private final InstanceAggregateMapper aggregateMapper;
  private final UserAgentIdentityMapper identityMapper;
  private final MiniappUserBindingMapper miniappBindingMapper;
  private final WechatUserCleanupOperationMapper cleanupMapper;

  public WechatUserQueryService(
      InstanceAggregateMapper aggregateMapper,
      UserAgentIdentityMapper identityMapper,
      MiniappUserBindingMapper miniappBindingMapper,
      WechatUserCleanupOperationMapper cleanupMapper
  ) {
    this.aggregateMapper = aggregateMapper;
    this.identityMapper = identityMapper;
    this.miniappBindingMapper = miniappBindingMapper;
    this.cleanupMapper = cleanupMapper;
  }

  public List<PublicWechatUser> listUsers() {
    List<InstanceEntity> instances = safe(aggregateMapper.listAll());
    Map<String, InstanceEntity> instancesById = new LinkedHashMap<>();
    for (InstanceEntity instance : instances) {
      instancesById.put(instance.getId(), instance);
    }

    List<WechatAccountChannelEntity> channels = instancesById.isEmpty()
        ? List.of()
        : safe(aggregateMapper.listWechatAccountChannelsByInstanceIds(new ArrayList<>(instancesById.keySet())));
    Map<String, WechatAccountChannelEntity> channelsByAccount = new HashMap<>();
    for (WechatAccountChannelEntity channel : channels) {
      channelsByAccount.put(accountKey(channel.getInstanceId(), channel.getAccountId()), channel);
    }

    List<MiniappWechatBindingSummary> miniapps = instancesById.isEmpty()
        ? List.of()
        : safe(miniappBindingMapper.listWechatSummariesByInstanceIds(new ArrayList<>(instancesById.keySet())));
    Map<String, MiniappWechatBindingSummary> miniappsByUser = new HashMap<>();
    for (MiniappWechatBindingSummary miniapp : miniapps) {
      miniappsByUser.put(userKey(miniapp.instanceId(), miniapp.wechatUserId()), miniapp);
    }

    List<WechatUserCleanupOperationEntity> operations = safe(cleanupMapper.listActive());
    Map<String, WechatUserCleanupOperationEntity> operationsByAccount = new HashMap<>();
    Map<String, WechatUserCleanupOperationEntity> operationsByWechatUser = new HashMap<>();
    for (WechatUserCleanupOperationEntity operation : operations) {
      if (!text(operation.getAccountId()).isBlank()) {
        operationsByAccount.put(accountKey(operation.getInstanceId(), operation.getAccountId()), operation);
      }
      if (!text(operation.getWechatUserId()).isBlank()) {
        operationsByWechatUser.put(userKey(operation.getInstanceId(), operation.getWechatUserId()), operation);
      }
    }

    List<PublicWechatUser> result = new ArrayList<>();
    Set<String> includedOperations = new HashSet<>();
    for (WechatPairedAccountEntity account : safe(aggregateMapper.listAllWechatAccounts())) {
      InstanceEntity instance = instancesById.get(account.getInstanceId());
      UserAgentIdentityEntity identity = text(account.getWechatUserId()).isBlank()
          ? null
          : identityMapper.findByWechatUserId(account.getWechatUserId());
      WechatAccountChannelEntity channel = channelsByAccount.get(accountKey(account.getInstanceId(), account.getAccountId()));
      MiniappWechatBindingSummary miniapp = miniappsByUser.get(userKey(account.getInstanceId(), account.getWechatUserId()));
      WechatUserCleanupOperationEntity operation = operationsByAccount.get(accountKey(account.getInstanceId(), account.getAccountId()));
      if (operation == null) {
        operation = operationsByWechatUser.get(userKey(account.getInstanceId(), account.getWechatUserId()));
      }
      if (operation != null) includedOperations.add(operation.getOperationId());
      result.add(fromAccount(instance, account, identity, channel, miniapp, operation));
    }

    for (WechatUserCleanupOperationEntity operation : operations) {
      if (!includedOperations.contains(operation.getOperationId())) {
        result.add(fromOperation(instancesById.get(operation.getInstanceId()), operation));
      }
    }
    return List.copyOf(result);
  }

  private static PublicWechatUser fromAccount(
      InstanceEntity instance,
      WechatPairedAccountEntity account,
      UserAgentIdentityEntity identity,
      WechatAccountChannelEntity channel,
      MiniappWechatBindingSummary miniapp,
      WechatUserCleanupOperationEntity operation
  ) {
    String state = recordState(operation);
    return new PublicWechatUser(
        account.getInstanceId(), value(instance, InstanceEntity::getName), value(instance, InstanceEntity::getStatus),
        account.getAccountId(), account.getPhone(), account.getWechatUserId(),
        identity == null ? null : identity.getAgentId(),
        identity == null ? null : identity.getOpenvikingUserId(),
        account.getRemark(), account.getBaseUrl(), account.getBoundAt(), account.getUpdatedAt(),
        channel == null ? "unknown" : channel.getStatus(), channel == null ? null : channel.getMessage(),
        channel == null ? null : channel.getUpdatedAt(), channel == null ? null : channel.getLastStartedAt(),
        channel == null ? null : channel.getLastErrorAt(),
        miniapp == null ? null : miniapp.openid(), miniapp == null ? null : miniapp.bindStatus(),
        miniapp == null ? null : miniapp.keyPreview(), miniapp != null && miniapp.keyEnabled(),
        miniapp == null ? null : miniapp.lastUsedAt(), state,
        operation == null ? null : operation.getOperationId(), operation == null ? null : operation.getStage(),
        operation != null && "cleanup_failed".equals(operation.getStatus()),
        operation == null ? null : operation.getLastError(), List.of()
    );
  }

  private static PublicWechatUser fromOperation(
      InstanceEntity instance, WechatUserCleanupOperationEntity operation) {
    return new PublicWechatUser(
        operation.getInstanceId(), value(instance, InstanceEntity::getName), value(instance, InstanceEntity::getStatus),
        operation.getAccountId(), operation.getPhone(), operation.getWechatUserId(), operation.getAgentId(),
        operation.getOpenvikingUserId(), null, null, null, operation.getUpdatedAt(),
        "unknown", null, null, null, null,
        null, null, null, false, null, recordState(operation), operation.getOperationId(), operation.getStage(),
        "cleanup_failed".equals(operation.getStatus()), operation.getLastError(), List.of("cleanup_operation")
    );
  }

  private static String recordState(WechatUserCleanupOperationEntity operation) {
    if (operation == null) {
      return "active";
    }
    return "cleanup_failed".equals(operation.getStatus()) ? "cleanup_failed" : "cleaning";
  }

  private static String accountKey(String instanceId, String accountId) {
    return text(instanceId) + "\0" + text(accountId);
  }

  private static String userKey(String instanceId, String wechatUserId) {
    return text(instanceId) + "\0" + text(wechatUserId);
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static <T> String value(T value, java.util.function.Function<T, String> getter) {
    return value == null ? null : getter.apply(value);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }
}
