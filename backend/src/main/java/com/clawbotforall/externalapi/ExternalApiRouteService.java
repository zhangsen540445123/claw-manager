package com.clawbotforall.externalapi;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalApiRouteService {

  private final ExternalApiUserRouteMapper routeMapper;
  private final InstanceAggregateMapper instanceMapper;
  private final OpenClawRuntime openClawRuntime;
  private final OpenVikingSettingsService openVikingSettingsService;
  private final ExternalApiIdentityService identityService;
  private final ApiChannelPluginService apiPluginService;
  private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

  public ExternalApiRouteService(
      ExternalApiUserRouteMapper routeMapper,
      InstanceAggregateMapper instanceMapper,
      OpenClawRuntime openClawRuntime,
      OpenVikingSettingsService openVikingSettingsService,
      ExternalApiIdentityService identityService,
      ApiChannelPluginService apiPluginService
  ) {
    this.routeMapper = routeMapper;
    this.instanceMapper = instanceMapper;
    this.openClawRuntime = openClawRuntime;
    this.openVikingSettingsService = openVikingSettingsService;
    this.identityService = identityService;
    this.apiPluginService = apiPluginService;
  }

  @Transactional
  public ExternalApiResolvedRoute resolveOrCreateRoute(String openid) {
    OpenVikingEffectiveSettings settings = openVikingSettingsService.effectiveSettings();
    ExternalApiIdentity identity = identityService.resolve(openid, settings.identityHashSecret());
    Object lock = locks.computeIfAbsent(identity.openidHash(), ignored -> new Object());
    synchronized (lock) {
      ExternalApiUserRouteEntity existing = routeMapper.findByOpenidHash(identity.openidHash());
      if (existing != null) {
        routeMapper.updateLastUsed(identity.openidHash(), Instant.now().toString());
        InstanceEntity instance = requireUsableInstance(existing.getInstanceId());
        return new ExternalApiResolvedRoute(instance, identity.openidHash(), existing.getOpenvikingUserId(), identity.senderId());
      }
      InstanceEntity selected = selectLeastLoadedInstance();
      String now = Instant.now().toString();
      ExternalApiUserRouteEntity route = new ExternalApiUserRouteEntity();
      route.setOpenid(identity.openid());
      route.setOpenidHash(identity.openidHash());
      route.setOpenvikingUserId(identity.openvikingUserId());
      route.setInstanceId(selected.getId());
      route.setCreatedAt(now);
      route.setUpdatedAt(now);
      route.setLastUsedAt(now);
      routeMapper.insert(route);
      return new ExternalApiResolvedRoute(selected, identity.openidHash(), identity.openvikingUserId(), identity.senderId());
    }
  }

  public String conversationHash(String conversationId) {
    return identityService.conversationHash(
        conversationId,
        openVikingSettingsService.effectiveSettings().identityHashSecret()
    );
  }

  public InstanceEntity requireUsableInstance(String instanceId) {
    InstanceEntity instance = instanceMapper.findById(instanceId);
    if (instance == null) {
      throw new ApiException(HttpStatus.CONFLICT, "该 openid 绑定的 OpenClaw 实例不存在，请在后台迁移路由。");
    }
    RuntimeState state = openClawRuntime.inspectInstance(instance);
    if (!state.running()) {
      throw new ApiException(HttpStatus.CONFLICT, "该 openid 绑定的 OpenClaw 实例当前不可用，请稍后重试或在后台迁移路由。");
    }
    if (!apiPluginService.isInstalled(instance)) {
      throw new ApiException(HttpStatus.CONFLICT, "该 openid 绑定的 OpenClaw 实例未安装 API Channel 插件。");
    }
    List<InstanceProvisioningEntity> provisioning = instanceMapper.listProvisioningByInstanceIds(List.of(instance.getId()));
    String status = provisioning.isEmpty() ? "" : defaultString(provisioning.getFirst().getStatus());
    if (!"ready".equals(status)) {
      throw new ApiException(HttpStatus.CONFLICT, "该 openid 绑定的 OpenClaw 实例尚未就绪，请稍后重试或在后台迁移路由。");
    }
    return instance;
  }

  private InstanceEntity selectLeastLoadedInstance() {
    List<InstanceEntity> candidates = instanceMapper.listRuntimeActive();
    if (candidates == null || candidates.isEmpty()) {
      throw new ApiException(HttpStatus.CONFLICT, "没有可用的 OpenClaw 实例可承载 API 用户。");
    }
    List<String> candidateIds = candidates.stream().map(InstanceEntity::getId).toList();
    Map<String, String> provisioningByInstanceId = new HashMap<>();
    for (InstanceProvisioningEntity provisioning : instanceMapper.listProvisioningByInstanceIds(candidateIds)) {
      provisioningByInstanceId.put(provisioning.getInstanceId(), provisioning.getStatus());
    }

    return candidates.stream()
        .filter(instance -> isReady(instance, provisioningByInstanceId))
        .map(instance -> new InstanceLoad(instance, totalBoundUsers(instance)))
        .min(Comparator
            .comparingInt(InstanceLoad::totalBoundUsers)
            .thenComparing(load -> defaultString(load.instance().getCreatedAt()))
            .thenComparing(load -> load.instance().getId()))
        .map(InstanceLoad::instance)
        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "没有已就绪且安装 API Channel 插件的 OpenClaw 实例。"));
  }

  private boolean isReady(InstanceEntity instance, Map<String, String> provisioningByInstanceId) {
    RuntimeState state = openClawRuntime.inspectInstance(instance);
    if (!state.running()) {
      return false;
    }
    if (!"ready".equals(provisioningByInstanceId.get(instance.getId()))) {
      return false;
    }
    return apiPluginService.isInstalled(instance);
  }

  private int totalBoundUsers(InstanceEntity instance) {
    return instanceMapper.countWechatAccountsByInstanceId(instance.getId())
        + routeMapper.countByInstanceId(instance.getId());
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }

  private record InstanceLoad(InstanceEntity instance, int totalBoundUsers) {}
}
