package com.clawbotforall.instance;

import com.clawbotforall.web.ApiException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 为 OpenClaw 实例面板分配可用宿主机端口。
 */
@Component
public class InstancePortAllocator {

  private final InstanceMutationMapper instanceMutationMapper;

  public InstancePortAllocator(InstanceMutationMapper instanceMutationMapper) {
    this.instanceMutationMapper = instanceMutationMapper;
  }

  /**
   * 从实例端口池中查找数据库未占用且宿主机可绑定的端口。
   */

  public int findAvailablePort() {
    Set<Integer> usedPorts = new HashSet<>(instanceMutationMapper.listUsedPorts());
    int startPort = InstanceRecordFactory.INSTANCE_BASE_PORT + 1;
    for (int candidate = startPort; candidate < startPort + 5000; candidate += 1) {
      if (!usedPorts.contains(candidate) && isTcpPortAvailable(candidate)) {
        return candidate;
      }
    }
    throw new ApiException(HttpStatus.CONFLICT, "未找到可用实例端口。请检查宿主机端口占用情况。");
  }

  private boolean isTcpPortAvailable(int port) {
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress("0.0.0.0", port));
      return true;
    } catch (IOException ignored) {
      return false;
    }
  }
}
