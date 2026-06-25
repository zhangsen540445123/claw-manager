package com.clawbotforall.plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Guards OpenClaw plugin operations because the same runner cannot safely run
 * multiple plugin mutations at once.
 */
@Component
public class PluginOperationCoordinator {

  private final ConcurrentMap<String, String> owners = new ConcurrentHashMap<>();

  public boolean tryStart(String instanceId, String owner) {
    return owners.putIfAbsent(instanceId, owner) == null;
  }

  public void finish(String instanceId, String owner) {
    owners.remove(instanceId, owner);
  }

  public String currentOwner(String instanceId) {
    return owners.getOrDefault(instanceId, "");
  }
}
