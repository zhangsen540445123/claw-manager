package com.clawbotforall.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.clawbotforall.config.ClawbotProperties;
import com.clawbotforall.instance.InstanceAggregateMapper;
import com.clawbotforall.instance.InstanceEntity;
import com.clawbotforall.instance.InstanceProvisioningEntity;
import com.clawbotforall.openviking.OpenVikingEffectiveSettings;
import com.clawbotforall.openviking.OpenVikingSettingsService;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import com.clawbotforall.web.ApiException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalApiRouteServiceTest {

  @TempDir
  Path tempDir;

  @Mock
  InstanceAggregateMapper instanceMapper;

  @Mock
  OpenClawRuntime openClawRuntime;

  @Mock
  OpenVikingSettingsService openVikingSettingsService;

  @Mock
  ApiChannelPluginService apiPluginService;

  InMemoryRouteMapper routeMapper;
  ExternalApiIdentityService identityService;
  ExternalApiRouteService service;

  @BeforeEach
  void setUp() {
    routeMapper = new InMemoryRouteMapper();
    identityService = new ExternalApiIdentityService(properties());
    service = new ExternalApiRouteService(
        routeMapper,
        instanceMapper,
        openClawRuntime,
        openVikingSettingsService,
        identityService,
        apiPluginService
    );
    when(openVikingSettingsService.effectiveSettings()).thenReturn(settings());
  }

  @Test
  void firstOpenidBindsToInstanceWithSmallestWechatPlusApiUserCount() {
    InstanceEntity instA = instance("inst_a", "2026-06-01T00:00:00Z");
    InstanceEntity instB = instance("inst_b", "2026-06-02T00:00:00Z");
    when(instanceMapper.listRuntimeActive()).thenReturn(List.of(instA, instB));
    when(openClawRuntime.inspectInstance(instA)).thenReturn(new RuntimeState(true, "running", "now"));
    when(openClawRuntime.inspectInstance(instB)).thenReturn(new RuntimeState(true, "running", "now"));
    when(instanceMapper.listProvisioningByInstanceIds(List.of("inst_a", "inst_b")))
        .thenReturn(List.of(ready("inst_a"), ready("inst_b")));
    when(instanceMapper.countWechatAccountsByInstanceId("inst_a")).thenReturn(1);
    when(instanceMapper.countWechatAccountsByInstanceId("inst_b")).thenReturn(0);
    routeMapper.counts.put("inst_a", 0);
    routeMapper.counts.put("inst_b", 2);
    when(apiPluginService.isInstalled(instA)).thenReturn(true);
    when(apiPluginService.isInstalled(instB)).thenReturn(true);

    ExternalApiResolvedRoute resolved = service.resolveOrCreateRoute(" openid_A ");

    assertThat(resolved.instance().getId()).isEqualTo("inst_a");
    assertThat(resolved.openvikingUserId()).startsWith("api_");
    assertThat(routeMapper.rows).hasSize(1);
    assertThat(routeMapper.rows.values().iterator().next().getOpenid()).isEqualTo("openid_A");
  }

  @Test
  void existingOpenidReusesBoundInstanceEvenWhenAnotherInstanceIsNowLighter() {
    InstanceEntity instA = instance("inst_a", "2026-06-01T00:00:00Z");
    ExternalApiResolvedRoute first = seedRoute("openid_A", "inst_a");
    when(instanceMapper.findById("inst_a")).thenReturn(instA);
    when(openClawRuntime.inspectInstance(instA)).thenReturn(new RuntimeState(true, "running", "now"));
    when(instanceMapper.listProvisioningByInstanceIds(List.of("inst_a"))).thenReturn(List.of(ready("inst_a")));
    when(apiPluginService.isInstalled(instA)).thenReturn(true);

    ExternalApiResolvedRoute resolved = service.resolveOrCreateRoute("openid_A");

    assertThat(resolved.instance().getId()).isEqualTo("inst_a");
    assertThat(resolved.openvikingUserId()).isEqualTo(first.openvikingUserId());
  }

  @Test
  void rejectsWhenNoReadyInstanceHasApiPluginInstalled() {
    InstanceEntity instA = instance("inst_a", "2026-06-01T00:00:00Z");
    when(instanceMapper.listRuntimeActive()).thenReturn(List.of(instA));
    when(openClawRuntime.inspectInstance(instA)).thenReturn(new RuntimeState(true, "running", "now"));
    when(instanceMapper.listProvisioningByInstanceIds(List.of("inst_a"))).thenReturn(List.of(ready("inst_a")));
    when(apiPluginService.isInstalled(instA)).thenReturn(false);

    assertThatThrownBy(() -> service.resolveOrCreateRoute("openid_A"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("API Channel 插件");
  }

  @Test
  void concurrentFirstOpenidsAreBalancedAcrossLeastLoadedInstances() throws Exception {
    InstanceEntity instA = instance("inst_a", "2026-06-01T00:00:00Z");
    InstanceEntity instB = instance("inst_b", "2026-06-02T00:00:00Z");
    routeMapper.insertDelayMs = 50;
    when(instanceMapper.listRuntimeActive()).thenReturn(List.of(instA, instB));
    when(openClawRuntime.inspectInstance(instA)).thenReturn(new RuntimeState(true, "running", "now"));
    when(openClawRuntime.inspectInstance(instB)).thenReturn(new RuntimeState(true, "running", "now"));
    when(instanceMapper.listProvisioningByInstanceIds(List.of("inst_a", "inst_b")))
        .thenReturn(List.of(ready("inst_a"), ready("inst_b")));
    when(instanceMapper.countWechatAccountsByInstanceId("inst_a")).thenReturn(0);
    when(instanceMapper.countWechatAccountsByInstanceId("inst_b")).thenReturn(0);
    when(apiPluginService.isInstalled(instA)).thenReturn(true);
    when(apiPluginService.isInstalled(instB)).thenReturn(true);

    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<String>> tasks = IntStream.rangeClosed(1, 10)
        .mapToObj(index -> (Callable<String>) () -> {
          start.await();
          return service.resolveOrCreateRoute("openid_" + index).instance().getId();
        })
        .toList();

    List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
    for (Callable<String> task : tasks) {
      futures.add(executor.submit(task));
    }
    start.countDown();
    Map<String, Long> distribution = new HashMap<>();
    for (java.util.concurrent.Future<String> future : futures) {
      distribution.merge(future.get(5, TimeUnit.SECONDS), 1L, Long::sum);
    }
    executor.shutdownNow();

    assertThat(distribution).containsEntry("inst_a", 5L).containsEntry("inst_b", 5L);
  }

  private ExternalApiResolvedRoute seedRoute(String openid, String instanceId) {
    ExternalApiIdentity identity = identityService.resolve(openid, "secret");
    ExternalApiUserRouteEntity entity = new ExternalApiUserRouteEntity();
    entity.setOpenid(openid);
    entity.setOpenidHash(identity.openidHash());
    entity.setOpenvikingUserId(identity.openvikingUserId());
    entity.setInstanceId(instanceId);
    entity.setCreatedAt("now");
    entity.setUpdatedAt("now");
    entity.setLastUsedAt("now");
    routeMapper.insert(entity);
    return new ExternalApiResolvedRoute(instance(instanceId, "now"), identity.openidHash(), identity.openvikingUserId(), "api:" + identity.openidHash());
  }

  private InstanceEntity instance(String id, String createdAt) {
    InstanceEntity instance = new InstanceEntity();
    instance.setId(id);
    instance.setName(id);
    instance.setStatus("running");
    instance.setCreatedAt(createdAt);
    return instance;
  }

  private InstanceProvisioningEntity ready(String instanceId) {
    InstanceProvisioningEntity provisioning = new InstanceProvisioningEntity();
    provisioning.setInstanceId(instanceId);
    provisioning.setStatus("ready");
    return provisioning;
  }

  private OpenVikingEffectiveSettings settings() {
    return new OpenVikingEffectiveSettings(
        "http://openviking:1933",
        false,
        "claw-manager",
        "secret",
        "npm:@claw-manager/openviking-openclaw-plugin@2026.6.37",
        "root-key",
        "broker-token",
        "http://claw-manager-api:8080"
    );
  }

  private ClawbotProperties properties() {
    return new ClawbotProperties(
        new ClawbotProperties.Paths(tempDir.toString()),
        new ClawbotProperties.Admin("", "平台管理员", ""),
        new ClawbotProperties.Security("clawbot_session", 14),
        new ClawbotProperties.Runtime(
            "runner:latest",
            600_000,
            "1.0",
            "1g",
            600_000,
            120_000,
            1_800_000,
            10_000,
            5_000,
            List.of()
        )
    );
  }

  private static class InMemoryRouteMapper implements ExternalApiUserRouteMapper {
    final Map<String, ExternalApiUserRouteEntity> rows = new ConcurrentHashMap<>();
    final Map<String, Integer> counts = new ConcurrentHashMap<>();
    final ReentrantLock allocationLock = new ReentrantLock();
    long insertDelayMs;

    @Override
    public ExternalApiUserRouteEntity findByOpenidHash(String openidHash) {
      return rows.get(openidHash);
    }

    @Override
    public List<ExternalApiUserRouteEntity> list(String keyword, String instanceId, int limit, int offset) {
      return List.copyOf(rows.values());
    }

    @Override
    public int count(String keyword, String instanceId) {
      return rows.size();
    }

    @Override
    public int countByInstanceId(String instanceId) {
      return counts.getOrDefault(instanceId, (int) rows.values().stream().filter(row -> instanceId.equals(row.getInstanceId())).count());
    }

    @Override
    public String lockAllocationRowForUpdate(String id) {
      allocationLock.lock();
      return id;
    }

    @Override
    public int insert(ExternalApiUserRouteEntity route) {
      if (insertDelayMs > 0) {
        try {
          Thread.sleep(insertDelayMs);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
      }
      try {
        rows.put(route.getOpenidHash(), route);
        return 1;
      } finally {
        if (allocationLock.isHeldByCurrentThread()) {
          allocationLock.unlock();
        }
      }
    }

    @Override
    public int updateLastUsed(String openidHash, String lastUsedAt) {
      ExternalApiUserRouteEntity route = rows.get(openidHash);
      if (route != null) {
        route.setLastUsedAt(lastUsedAt);
      }
      return route == null ? 0 : 1;
    }

    @Override
    public int updateInstance(String openidHash, String instanceId, String updatedAt) {
      ExternalApiUserRouteEntity route = rows.get(openidHash);
      if (route != null) {
        route.setInstanceId(instanceId);
        route.setUpdatedAt(updatedAt);
      }
      return route == null ? 0 : 1;
    }
  }
}
