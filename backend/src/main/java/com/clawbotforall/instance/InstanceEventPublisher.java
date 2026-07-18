package com.clawbotforall.instance;

import com.clawbotforall.runtime.InstanceStats;
import com.clawbotforall.externalapi.PublicApiChannelPluginStatus;
import com.clawbotforall.openviking.PublicOpenVikingPluginStatus;
import com.clawbotforall.wechat.PublicWechatBindLink;
import com.clawbotforall.wechat.PublicWechatPluginStatus;
import com.clawbotforall.ws.AppEvent;
import com.clawbotforall.ws.AppEventPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 向管理员 WebSocket 主题发布实例、进度、统计和绑定事件。
 */
@Component
public class InstanceEventPublisher {

  private static final String ADMIN_INSTANCES_TOPIC = "/topic/admin/instances";
  private static final String ADMIN_STATS_TOPIC = "/topic/admin/instance-stats";
  private static final String ADMIN_WECHAT_TOPIC = "/topic/admin/wechat";
  private static final String ADMIN_MODEL_AUTH_TOPIC = "/topic/admin/model-auth";

  private final AppEventPublisher appEventPublisher;

  public InstanceEventPublisher(AppEventPublisher appEventPublisher) {
    this.appEventPublisher = appEventPublisher;
  }

  public void publishInstanceUpdated(PublicInstance instance) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instance", instance);
    appEventPublisher.sendToTopic(ADMIN_INSTANCES_TOPIC, AppEvent.of("instance.updated", traceId(), payload));
  }

  public void publishInstancesChanged() {
    appEventPublisher.sendToTopic(
        ADMIN_INSTANCES_TOPIC,
        AppEvent.of("admin.instances.updated", traceId(), Map.of("changed", true))
    );
  }

  public void publishProvisioningUpdated(String instanceId, InstanceProvisioningEntity provisioning) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("provisioning", new PublicInstanceProvisioning(
        provisioning.getStatus(),
        provisioning.getPercent(),
        provisioning.getStage(),
        provisioning.getMessage(),
        provisioning.getGatewayStartedAt(),
        provisioning.getUpdatedAt()
    ));
    appEventPublisher.sendToTopic(ADMIN_INSTANCES_TOPIC, AppEvent.of("instance.provisioning.updated", traceId(), payload));
  }

  public void publishStatsUpdated(String instanceId, InstanceStats stats) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("stats", stats);
    appEventPublisher.sendToTopic(ADMIN_STATS_TOPIC, AppEvent.of("instance.stats.updated", traceId(), payload));
  }

  public void publishWechatBindingUpdated(String instanceId, PublicWechatBinding binding) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("binding", binding);
    appEventPublisher.sendToTopic(ADMIN_WECHAT_TOPIC, AppEvent.of("wechat.binding.updated", traceId(), payload));
  }

  public void publishWechatPluginUpdated(String instanceId, PublicWechatPluginStatus plugin) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("plugin", plugin);
    appEventPublisher.sendToTopic(ADMIN_WECHAT_TOPIC, AppEvent.of("wechat.plugin.updated", traceId(), payload));
  }

  public void publishOpenVikingPluginUpdated(String instanceId, PublicOpenVikingPluginStatus plugin) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("plugin", plugin);
    appEventPublisher.sendToTopic(ADMIN_WECHAT_TOPIC, AppEvent.of("openviking.plugin.updated", traceId(), payload));
  }

  public void publishMiniappBridgePluginUpdated(String instanceId, PublicApiChannelPluginStatus plugin) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("plugin", plugin);
    appEventPublisher.sendToTopic(
        ADMIN_WECHAT_TOPIC,
        AppEvent.of("miniapp.bridge.plugin.updated", traceId(), payload)
    );
  }

  public void publishWorkspaceFilePluginUpdated(String instanceId, PublicApiChannelPluginStatus plugin) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("plugin", plugin);
    appEventPublisher.sendToTopic(
        ADMIN_WECHAT_TOPIC,
        AppEvent.of("workspace.file.plugin.updated", traceId(), payload)
    );
  }

  public void publishWechatBindLinkUpdated(String token, PublicWechatBindLink link) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("token", token);
    payload.put("link", link);
    appEventPublisher.sendToTopic(ADMIN_WECHAT_TOPIC, AppEvent.of("wechat.bindLink.updated", traceId(), payload));
  }

  public void publishModelAuthUpdated(String instanceId, PublicInstanceModelAuth modelAuth) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("instanceId", instanceId);
    payload.put("modelAuth", modelAuth);
    appEventPublisher.sendToTopic(ADMIN_MODEL_AUTH_TOPIC, AppEvent.of("modelAuth.updated", traceId(), payload));
  }

  private static String traceId() {
    return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
