package com.clawbotforall.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.RuntimeState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class ExternalApiRouteServiceMySqlIntegrationTest {

  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
      .withDatabaseName("clawbot_route_it")
      .withUsername("clawbot")
      .withPassword("clawbot");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
      .withExposedPorts(6379);

  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  ExternalApiRouteService routeService;

  @MockBean
  OpenClawRuntime openClawRuntime;

  @MockBean
  ApiChannelPluginService apiPluginService;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("clawbot.admin.email", () -> "route-admin@example.com");
    registry.add("clawbot.admin.name", () -> "Route Admin");
    registry.add("clawbot.admin.password", () -> "RouteAdmin123!");
    registry.add("clawbot.paths.data-dir", () -> "target/route-it-data");
    registry.add("clawbot.runtime.stats-poll-interval-ms", () -> "3600000");
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM external_api_user_routes");
    jdbcTemplate.update("DELETE FROM wechat_paired_accounts");
    jdbcTemplate.update("DELETE FROM instance_provisioning");
    jdbcTemplate.update("DELETE FROM instances");
    jdbcTemplate.update("DELETE FROM openviking_settings");
    insertOpenVikingSettings();
    insertReadyInstance("inst_a", "2026-06-01T00:00:00Z");
    insertReadyInstance("inst_b", "2026-06-02T00:00:00Z");
    when(openClawRuntime.inspectInstance(org.mockito.ArgumentMatchers.argThat(instance -> instance != null && "inst_a".equals(instance.getId()))))
        .thenReturn(new RuntimeState(true, "running", "now"));
    when(openClawRuntime.inspectInstance(org.mockito.ArgumentMatchers.argThat(instance -> instance != null && "inst_b".equals(instance.getId()))))
        .thenReturn(new RuntimeState(true, "running", "now"));
    when(apiPluginService.isInstalled(org.mockito.ArgumentMatchers.any())).thenReturn(true);
  }

  @Test
  void concurrentFirstOpenidsAreBalancedAcrossReadyInstancesWithRealMySqlTransactions() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<String>> tasks = IntStream.rangeClosed(1, 10)
        .mapToObj(index -> (Callable<String>) () -> {
          start.await();
          return routeService.resolveOrCreateRoute("route-it-openid-" + index).instance().getId();
        })
        .toList();

    List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
    for (Callable<String> task : tasks) {
      futures.add(executor.submit(task));
    }
    start.countDown();
    Map<String, Long> distribution = new HashMap<>();
    for (java.util.concurrent.Future<String> future : futures) {
      distribution.merge(future.get(20, TimeUnit.SECONDS), 1L, Long::sum);
    }
    executor.shutdownNow();

    assertThat(distribution).containsEntry("inst_a", 5L).containsEntry("inst_b", 5L);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM external_api_user_routes WHERE instance_id = 'inst_a'",
        Long.class
    )).isEqualTo(5L);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM external_api_user_routes WHERE instance_id = 'inst_b'",
        Long.class
    )).isEqualTo(5L);
  }

  private void insertOpenVikingSettings() {
    String now = Instant.now().toString();
    jdbcTemplate.update(
        """
            INSERT INTO openviking_settings
              (id, base_url, trusted_mode_enabled, account_id, plugin_package, identity_salt, root_api_key, created_at, updated_at)
            VALUES
              ('global', 'http://openviking:1933', 1, 'claw-manager',
               'npm:@claw-manager/openviking-openclaw-plugin@2026.6.37',
               'route-test-salt', 'root-key', ?, ?)
            """,
        now,
        now
    );
  }

  private void insertReadyInstance(String id, String createdAt) {
    String now = Instant.now().toString();
    jdbcTemplate.update(
        """
            INSERT INTO instances
              (id, name, slug, status, port, dashboard_url, container_name, gateway_token,
               plugins_allow, plugins_entries, created_at, updated_at)
            VALUES
              (?, ?, ?, 'running', ?, '', ?, 'token', JSON_ARRAY('claw-manager-api'), JSON_OBJECT(), ?, ?)
            """,
        id,
        id,
        id,
        "inst_a".equals(id) ? 19001 : 19002,
        "container-" + id,
        createdAt,
        now
    );
    jdbcTemplate.update(
        """
            INSERT INTO instance_provisioning
              (instance_id, status, percent, stage, message, gateway_started_at, updated_at)
            VALUES
              (?, 'ready', 100, 'ready', 'ready', ?, ?)
            """,
        id,
        now,
        now
    );
  }
}
