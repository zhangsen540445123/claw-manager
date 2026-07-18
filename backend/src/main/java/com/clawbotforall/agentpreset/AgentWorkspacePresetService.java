package com.clawbotforall.agentpreset;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.web.ApiException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Primary
public class AgentWorkspacePresetService implements AgentWorkspacePresetProvider {
  public static final String GLOBAL_ID = "global";
  private final AgentWorkspacePresetMapper mapper;
  private final InstanceAggregateMapper instances;
  private final AgentWorkspacePresetSnapshotWriter snapshotWriter;

  public AgentWorkspacePresetService(
      AgentWorkspacePresetMapper mapper,
      InstanceAggregateMapper instances,
      AgentWorkspacePresetSnapshotWriter snapshotWriter
  ) {
    this.mapper = mapper;
    this.instances = instances;
    this.snapshotWriter = snapshotWriter;
  }

  @Override
  @Transactional(readOnly = true)
  public AgentWorkspacePreset current() {
    AgentWorkspacePresetEntity entity = mapper.findGlobal();
    if (entity == null) return AgentWorkspacePreset.defaults();
    return toPreset(entity);
  }

  @Transactional(readOnly = true)
  public PublicAgentWorkspacePreset publicPreset() {
    AgentWorkspacePresetEntity entity = mapper.findGlobal();
    return PublicAgentWorkspacePreset.from(current(), entity == null ? "" : entity.getUpdatedAt());
  }

  @Transactional
  public PublicAgentWorkspacePreset update(Map<String, Object> payload) {
    AgentWorkspacePreset previous = current();
    int version = previous.version() + 1;
    AgentWorkspacePreset next = new AgentWorkspacePreset(
        version,
        text(payload, "agentsMd", previous.agentsMd()),
        text(payload, "soulMd", previous.soulMd()),
        text(payload, "identityMd", previous.identityMd()),
        text(payload, "toolsMd", previous.toolsMd()),
        text(payload, "heartbeatMd", previous.heartbeatMd()),
        text(payload, "userMd", previous.userMd())
    );
    String now = Instant.now().toString();
    AgentWorkspacePresetEntity entity = new AgentWorkspacePresetEntity();
    entity.setId(GLOBAL_ID);
    entity.setVersion(version);
    entity.setAgentsMd(next.agentsMd());
    entity.setSoulMd(next.soulMd());
    entity.setIdentityMd(next.identityMd());
    entity.setToolsMd(next.toolsMd());
    entity.setHeartbeatMd(next.heartbeatMd());
    entity.setUserMd(next.userMd());
    entity.setUpdatedAt(now);
    mapper.upsert(entity);
    instances.listAll().forEach(instance -> snapshotWriter.writeForInstance(instance.getId(), next));
    return PublicAgentWorkspacePreset.from(next, now);
  }

  private AgentWorkspacePreset toPreset(AgentWorkspacePresetEntity entity) {
    return new AgentWorkspacePreset(entity.getVersion(),
        normalize(entity.getAgentsMd(), AgentWorkspacePreset.defaults().agentsMd()),
        normalize(entity.getSoulMd(), AgentWorkspacePreset.defaults().soulMd()),
        normalize(entity.getIdentityMd(), AgentWorkspacePreset.defaults().identityMd()),
        normalize(entity.getToolsMd(), AgentWorkspacePreset.defaults().toolsMd()),
        normalize(entity.getHeartbeatMd(), AgentWorkspacePreset.defaults().heartbeatMd()),
        normalize(entity.getUserMd(), AgentWorkspacePreset.defaults().userMd()));
  }

  private static String text(Map<String, Object> payload, String key, String fallback) {
    if (payload == null || !payload.containsKey(key)) return fallback;
    Object value = payload.get(key);
    if (value == null) throw new ApiException(HttpStatus.BAD_REQUEST, key + " 不能为空。" );
    return normalize(String.valueOf(value), fallback);
  }

  private static String normalize(String value, String fallback) {
    String normalized = value == null ? "" : value.replace("\r\n", "\n").trim();
    if (normalized.isBlank()) return fallback;
    return normalized + "\n";
  }
}
