package com.clawbotforall.miniapp;

import com.clawbotforall.externalapi.ApiChannelPluginService;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MiniappInstanceService {
  private final InstanceAggregateMapper instanceMapper;
  private final MiniappUserBindingMapper bindingMapper;
  private final OpenClawRuntime openClawRuntime;
  private final ApiChannelPluginService apiPluginService;

  public MiniappInstanceService(
      InstanceAggregateMapper instanceMapper,
      MiniappUserBindingMapper bindingMapper,
      OpenClawRuntime openClawRuntime,
      ApiChannelPluginService apiPluginService
  ) {
    this.instanceMapper = instanceMapper;
    this.bindingMapper = bindingMapper;
    this.openClawRuntime = openClawRuntime;
    this.apiPluginService = apiPluginService;
  }

  public InstanceEntity selectLeastLoadedInstance() {
    List<InstanceEntity> candidates = instanceMapper.listRuntimeActive();
    if (candidates == null || candidates.isEmpty()) {
      throw new ApiException(HttpStatus.CONFLICT, "没有可用的 OpenClaw 实例可承载小程序用户。");
    }
    List<String> ids = candidates.stream().map(InstanceEntity::getId).toList();
    Map<String, String> provisioning = new HashMap<>();
    for (InstanceProvisioningEntity item : instanceMapper.listProvisioningByInstanceIds(ids)) {
      provisioning.put(item.getInstanceId(), item.getStatus());
    }
    return candidates.stream()
        .filter(instance -> isReady(instance, provisioning))
        .min(Comparator
            .comparingInt(this::totalBoundUsers)
            .thenComparing(instance -> defaultString(instance.getCreatedAt()))
            .thenComparing(InstanceEntity::getId))
        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "没有已就绪且安装 API Channel 插件的 OpenClaw 实例。"));
  }

  public InstanceEntity requireUsableApiInstance(String instanceId) {
    InstanceEntity instance = instanceMapper.findById(instanceId);
    if (instance == null) {
      throw new ApiException(HttpStatus.CONFLICT, "该小程序用户绑定的 OpenClaw 实例不存在。");
    }
    if (!isRunning(instance)) {
      throw new ApiException(HttpStatus.CONFLICT, "该小程序用户绑定的 OpenClaw 实例当前不可用。");
    }
    if (!apiPluginService.isInstalled(instance)) {
      throw new ApiException(HttpStatus.CONFLICT, "该小程序用户绑定的 OpenClaw 实例未安装 API Channel 插件。");
    }
    List<InstanceProvisioningEntity> provisioning = instanceMapper.listProvisioningByInstanceIds(List.of(instance.getId()));
    String status = provisioning.isEmpty() ? "" : defaultString(provisioning.getFirst().getStatus());
    if (!"ready".equals(status)) {
      throw new ApiException(HttpStatus.CONFLICT, "该小程序用户绑定的 OpenClaw 实例尚未就绪。");
    }
    return instance;
  }

  private boolean isReady(InstanceEntity instance, Map<String, String> provisioning) {
    return isRunning(instance)
        && "ready".equals(provisioning.get(instance.getId()))
        && apiPluginService.isInstalled(instance);
  }

  private boolean isRunning(InstanceEntity instance) {
    RuntimeState state = openClawRuntime.inspectInstance(instance);
    return state.running();
  }

  private int totalBoundUsers(InstanceEntity instance) {
    return instanceMapper.countWechatAccountsByInstanceId(instance.getId())
        + bindingMapper.countByInstanceId(instance.getId());
  }

  private static String defaultString(String value) {
    return value == null ? "" : value;
  }
}
