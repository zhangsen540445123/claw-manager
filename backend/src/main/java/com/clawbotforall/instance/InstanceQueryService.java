package com.clawbotforall.instance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import com.clawbotforall.wechat.WechatAccountSyncService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 构建公共实例视图，并在读取时同步微信账号状态。
 */
@Service
public class InstanceQueryService {

  private final InstanceAggregateMapper instanceAggregateMapper;
  private final PublicInstanceFactory publicInstanceFactory;
  private final WechatAccountSyncService wechatAccountSyncService;

  public InstanceQueryService(
      InstanceAggregateMapper instanceAggregateMapper,
      PublicInstanceFactory publicInstanceFactory,
      WechatAccountSyncService wechatAccountSyncService
  ) {
    this.instanceAggregateMapper = instanceAggregateMapper;
    this.publicInstanceFactory = publicInstanceFactory;
    this.wechatAccountSyncService = wechatAccountSyncService;
  }

  /**
   * 构建全部实例响应。
   */

  @Transactional
  public List<PublicInstance> listAllInstances(HttpServletRequest request) {
    List<InstanceEntity> instances = instanceAggregateMapper.listAll();
    if (instances.isEmpty()) {
      return List.of();
    }
    wechatAccountSyncService.syncInstances(instances);
    return publicInstances(instances, request);
  }

  /**
   * 为单个实例构建公共实例响应。
   */

  @Transactional
  public Optional<PublicInstance> findPublicInstance(String instanceId, HttpServletRequest request) {
    InstanceEntity instance = instanceAggregateMapper.findById(instanceId);
    if (instance == null) {
      return Optional.empty();
    }
    wechatAccountSyncService.syncInstanceAccounts(instance);
    return publicInstances(List.of(instance), request).stream().findFirst();
  }

  private List<PublicInstance> publicInstances(
      List<InstanceEntity> instances,
      HttpServletRequest request
  ) {
    List<String> instanceIds = instances.stream()
        .map(InstanceEntity::getId)
        .toList();
    Map<String, List<InstanceModelEntity>> modelsByInstance = instanceAggregateMapper
        .listModelsByInstanceIds(instanceIds)
        .stream()
        .collect(Collectors.groupingBy(InstanceModelEntity::getInstanceId));
    Map<String, InstanceProvisioningEntity> provisioningByInstance = instanceAggregateMapper
        .listProvisioningByInstanceIds(instanceIds)
        .stream()
        .collect(Collectors.toMap(InstanceProvisioningEntity::getInstanceId, item -> item));
    Map<String, InstanceModelAuthEntity> modelAuthByInstance = instanceAggregateMapper
        .listModelAuthByInstanceIds(instanceIds)
        .stream()
        .collect(Collectors.toMap(InstanceModelAuthEntity::getInstanceId, item -> item));
    Map<String, InstanceWechatBindingEntity> wechatBindingByInstance = instanceAggregateMapper
        .listWechatBindingByInstanceIds(instanceIds)
        .stream()
        .collect(Collectors.toMap(InstanceWechatBindingEntity::getInstanceId, item -> item));
    Map<String, List<WechatPairedAccountEntity>> pairedAccountsByInstance = instanceAggregateMapper
        .listWechatAccountsByInstanceIds(instanceIds)
        .stream()
        .collect(Collectors.groupingBy(WechatPairedAccountEntity::getInstanceId));

    return instances.stream()
        .map(instance -> publicInstanceFactory.from(
            instance,
            modelsByInstance.getOrDefault(instance.getId(), List.of()),
            provisioningByInstance.get(instance.getId()),
            modelAuthByInstance.get(instance.getId()),
            wechatBindingByInstance.get(instance.getId()),
            pairedAccountsByInstance.getOrDefault(instance.getId(), List.of()),
            request
        ))
        .toList();
  }
}
