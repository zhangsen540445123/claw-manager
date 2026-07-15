package com.clawbotforall.image;

import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceFileService;
import com.clawbotforall.instance.InstanceModelEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ImageGenerationSettingsSyncService {
  private final InstanceAggregateMapper instances;
  private final InstanceFileService files;

  public ImageGenerationSettingsSyncService(InstanceAggregateMapper instances, InstanceFileService files) {
    this.instances = instances;
    this.files = files;
  }

  public List<String> syncAll() {
    List<InstanceEntity> all = instances.listAll();
    if (all.isEmpty()) return List.of();
    Map<String, List<InstanceModelEntity>> models = new LinkedHashMap<>();
    for (InstanceModelEntity model : instances.listModelsByInstanceIds(all.stream().map(InstanceEntity::getId).toList())) {
      models.computeIfAbsent(model.getInstanceId(), ignored -> new ArrayList<>()).add(model);
    }
    List<String> synced = new ArrayList<>();
    for (InstanceEntity instance : all) {
      files.writeInstanceFiles(instance, models.getOrDefault(instance.getId(), List.of()));
      synced.add(instance.getId());
    }
    return List.copyOf(synced);
  }
}
