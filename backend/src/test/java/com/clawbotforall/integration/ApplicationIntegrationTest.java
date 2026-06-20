package com.clawbotforall.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clawbotforall.runtime.InstanceStats;
import com.clawbotforall.runtime.OpenClawRuntime;
import com.clawbotforall.runtime.ProxyTarget;
import com.clawbotforall.runtime.RunnerImageStatus;
import com.clawbotforall.runtime.RuntimeExecListener;
import com.clawbotforall.runtime.RuntimeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationIntegrationTest {

  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
      .withDatabaseName("clawbot_it")
      .withUsername("clawbot")
      .withPassword("clawbot");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
      .withExposedPorts(6379);

  @Autowired
  MockMvc mockMvc;

  @Autowired
  ObjectMapper objectMapper;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @MockBean
  OpenClawRuntime openClawRuntime;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("clawbot.admin.email", () -> "integration-admin@example.com");
    registry.add("clawbot.admin.name", () -> "Integration Admin");
    registry.add("clawbot.admin.password", () -> "Integration123!");
    registry.add("clawbot.runtime.stats-poll-interval-ms", () -> "3600000");
    registry.add("clawbot.paths.data-dir", () -> "target/integration-data");
    registry.add("clawbot.runtime.gateway-ready-timeout-ms", () -> "50");
    registry.add("clawbot.runtime.gateway-ready-check-interval-ms", () -> "10");
    registry.add("clawbot.runtime.gateway-ready-probe-timeout-ms", () -> "50");
  }

  @Test
  void bootsWithRealMySqlAndRedisThenAuthenticatesAdminCookieSession() throws Exception {
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Long.class))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admins", Long.class))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN ('users', 'invites', 'api_tokens')
            """,
        Long.class
    ))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM (
              SELECT index_name
              FROM information_schema.statistics
              WHERE table_schema = DATABASE()
                AND table_name = 'wechat_paired_accounts'
                AND non_unique = 0
              GROUP BY index_name
              HAVING COUNT(*) = 1
                 AND MAX(column_name) IN ('account_id', 'phone', 'wechat_user_id')
            ) unique_single_column_indexes
            """,
        Long.class
    ))
        .isEqualTo(3);
    assertThat(jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = 'instance_wechat_binding'
            """,
        Long.class
    ))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = 'wechat_account_channels'
            """,
        Long.class
    ))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'wechat_account_channels'
              AND non_unique = 0
              AND column_name = 'wechat_user_id'
            """,
        Long.class
    ))
        .isGreaterThanOrEqualTo(1);

    mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));

    MvcResult login = mockMvc.perform(post("/api/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"integration-admin@example.com","password":"Integration123!"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.email").value("integration-admin@example.com"))
        .andExpect(jsonPath("$.user.mustChangePassword").value(true))
        .andReturn();

    Cookie sessionCookie = login.getResponse().getCookie("clawbot_session");
    assertThat(sessionCookie).isNotNull();

    mockMvc.perform(post("/api/change-password")
            .cookie(sessionCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"","newPassword":"Integration456!"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.mustChangePassword").value(false));

    mockMvc.perform(get("/api/session")
            .cookie(sessionCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.email").value("integration-admin@example.com"))
        .andExpect(jsonPath("$.user.mustChangePassword").value(false));

    mockMvc.perform(post("/api/logout")
            .cookie(sessionCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
  }

  @Test
  void coversAdminInstanceModelAndWechatBindLinkApiFlows() throws Exception {
    RuntimeState runningState = new RuntimeState(true, "running", "2026-06-18T00:00:00Z");
    when(openClawRuntime.inspectInstance(any())).thenReturn(runningState);
    when(openClawRuntime.startInstance(any(), any())).thenReturn(runningState);
    when(openClawRuntime.getRunnerImageStatus()).thenReturn(new RunnerImageStatus(
        "runner:test", "ready", "ready", true, "sha256:test", "2026-06-16T00:00:00Z"
    ));
    when(openClawRuntime.refreshRunnerImage()).thenReturn(new RunnerImageStatus(
        "runner:test", "ready", "refreshed", true, "sha256:test", "2026-06-16T00:00:01Z"
    ));
    when(openClawRuntime.getLogs(any(), any(Integer.class))).thenReturn("line1\nline2");
    when(openClawRuntime.getStats(any())).thenReturn(new InstanceStats("1.2", "12 MiB / 1 GiB", "1.1", "0B / 0B", "4"));
    when(openClawRuntime.resolveProxyTarget(any())).thenReturn(new ProxyTarget("127.0.0.1", 19001, "test", ""));
    when(openClawRuntime.startExec(any(), any(java.util.List.class), anyLong(), anyMap(), any(RuntimeExecListener.class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> command = invocation.getArgument(1);
          RuntimeExecListener listener = invocation.getArgument(4);
          if (command.stream().anyMatch(part -> part.contains("startWeixinLoginWithQr"))) {
            listener.onOutput("__OPENCLAW_WECHAT_BIND__{\"type\":\"qr\",\"requestedAccountId\":\"cmwx_integration\",\"sessionKey\":\"cmwx_integration\",\"qrLink\":\"https://liteapp.weixin.qq.com/q/integration\"}\n");
          } else {
            listener.onOutput("{\"channel\":\"openclaw-weixin\",\"started\":true}\n");
            listener.onComplete(0);
          }
          return new com.clawbotforall.runtime.RuntimeExecHandle() {
            @Override
            public void sendInput(String input) {}

            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    Cookie adminCookie = loginAdmin();

    MvcResult presetResponse = mockMvc.perform(post("/api/admin/model-presets")
            .cookie(adminCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name":"Integration Preset",
                  "providerKey":"custom-provider",
                  "providerId":"openai",
                  "modelId":"gpt-5.5",
                  "apiMode":"openai-responses",
                  "baseUrl":"https://example.test/v1",
                  "apiKey":"sk-test",
                  "isDefault":true
                }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.preset.name").value("Integration Preset"))
        .andExpect(jsonPath("$.preset.isDefault").value(true))
        .andReturn();
    String presetId = objectMapper.readTree(presetResponse.getResponse().getContentAsString())
        .at("/preset/id").asText();

    mockMvc.perform(post("/api/admin/runner-image/refresh").cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.image.status").value("ready"));

    mockMvc.perform(post("/api/admin/instances")
            .cookie(adminCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"No Model\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("请选择模型预设。"));

    MvcResult instanceResponse = mockMvc.perform(post("/api/admin/instances")
            .cookie(adminCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Flow Instance\",\"presetId\":\"%s\"}".formatted(presetId)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.instance.name").value("Flow Instance"))
        .andExpect(jsonPath("$.instance.provisioning.status").value("running"))
        .andExpect(jsonPath("$.instance.provisioning.percent").value(5))
        .andExpect(jsonPath("$.instance.models[0].presetId").value(presetId))
        .andExpect(jsonPath("$.instance.models[0].modelId").value("gpt-5.5"))
        .andExpect(jsonPath("$.instance.modelChain[0].modelId").value("gpt-5.5"))
        .andReturn();
    String instanceId = objectMapper.readTree(instanceResponse.getResponse().getContentAsString())
        .at("/instance/id").asText();
    Path pluginPackage = Path.of(
        "target",
        "integration-data",
        "instances",
        instanceId,
        "home",
        ".openclaw",
        "extensions",
        "openclaw-weixin",
        "package.json"
    );
    Files.createDirectories(pluginPackage.getParent());
    Files.writeString(pluginPackage, "{\"version\":\"2.5.0\"}");

    mockMvc.perform(get("/api/admin/instances/" + instanceId + "/logs").cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.logs").value("line1\nline2"));

    mockMvc.perform(get("/api/admin/instances/" + instanceId + "/stats").cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats.cpuPercent").value("1.2"));

    mockMvc.perform(post("/api/admin/instances/" + instanceId + "/models")
            .cookie(adminCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "providerKey":"custom-provider",
                  "providerId":"anthropic",
                  "modelId":"claude-test",
                  "apiMode":"anthropic-messages",
                  "apiKey":"sk-ant-test"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance.models.length()").value(2))
        .andExpect(jsonPath("$.instance.modelChain.length()").value(2));

    mockMvc.perform(post("/api/admin/instances/" + instanceId + "/models/reorder")
            .cookie(adminCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"index\":1,\"direction\":\"up\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance.models[0].modelId").value("claude-test"));

    mockMvc.perform(post("/api/admin/instances/" + instanceId + "/models/1/primary").cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance.models[0].modelId").value("gpt-5.5"));

    mockMvc.perform(delete("/api/admin/instances/" + instanceId + "/models/1").cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance.models.length()").value(1));

    mockMvc.perform(put("/api/admin/instances/" + instanceId + "/wechat-accounts/missing")
            .cookie(adminCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"remark\":\"备注\"}"))
        .andExpect(status().isNotFound());

    mockMvc.perform(post("/api/admin/instances/" + instanceId + "/wechat-unbind").cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance.wechatBinding.status").value("idle"));

    jdbcTemplate.update(
        """
            UPDATE instances
            SET status = 'running'
            WHERE id = ?
            """,
        instanceId
    );
    jdbcTemplate.update(
        """
            UPDATE instance_provisioning
            SET status = 'ready',
                percent = 100,
                stage = 'ready',
                message = 'Gateway 已就绪。'
            WHERE instance_id = ?
            """,
        instanceId
    );

    mockMvc.perform(get("/api/admin/instances").cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instances[?(@.id=='" + instanceId + "')]").exists());

    MvcResult newBindLinkResponse = mockMvc.perform(post("/api/admin/wechat-bind-links")
            .cookie(adminCookie)
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "admin.example.test")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"new\",\"phone\":\"13900000002\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.mode").value("new"))
        .andExpect(jsonPath("$.link.modeLabel").value("新用户"))
        .andExpect(jsonPath("$.link.status").value("created"))
        .andExpect(jsonPath("$.link.statusLabel").value("已创建"))
        .andExpect(jsonPath("$.link.phone").value("13900000002"))
        .andExpect(jsonPath("$.link.expiresAt").exists())
        .andExpect(jsonPath("$.link.bindLink").value(org.hamcrest.Matchers.startsWith("https://admin.example.test/bind/")))
        .andReturn();
    String newBindToken = objectMapper.readTree(newBindLinkResponse.getResponse().getContentAsString())
        .at("/link/token").asText();
    waitForAdminLinkStatus(adminCookie, newBindToken, "waiting_scan");

    mockMvc.perform(get("/api/public/wechat-bind-links/" + newBindToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.status").value("waiting_scan"))
        .andExpect(jsonPath("$.link.qrLink").value("https://liteapp.weixin.qq.com/q/integration"))
        .andExpect(jsonPath("$.link.message").value("请使用微信扫描二维码完成绑定。"));

    mockMvc.perform(get("/api/admin/wechat-bind-links")
            .cookie(adminCookie)
            .param("mode", "new")
            .param("status", "waiting_scan")
            .param("page", "1")
            .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.links[0].statusLabel").value("等待扫码"));

    mockMvc.perform(get("/api/admin/wechat-bind-links/" + newBindToken)
            .cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.token").value(newBindToken))
        .andExpect(jsonPath("$.link.expiresAt").exists());

    mockMvc.perform(post("/api/admin/wechat-bind-links/" + newBindToken + "/revoke")
            .cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.status").value("revoked"))
        .andExpect(jsonPath("$.link.statusLabel").value("已失效"));

    mockMvc.perform(get("/api/public/wechat-bind-links/" + newBindToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.status").value("revoked"))
        .andExpect(jsonPath("$.link.statusLabel").value("已失效"));

    jdbcTemplate.update(
        """
            INSERT INTO wechat_paired_accounts
              (account_id, phone, instance_id, wechat_user_id, remark, base_url, saved_at, bound_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        "wx_existing",
        "13572873189",
        instanceId,
        "wechat-user-existing",
        "老用户",
        "https://wechat.example.test",
        "2026-06-18T00:00:00Z",
        "2026-06-18T00:00:00Z",
        "2026-06-18T00:00:00Z"
    );

    mockMvc.perform(get("/api/admin/wechat-bindings")
            .cookie(adminCookie)
            .param("phone", "13572873189"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.binding.accountId").value("wx_existing"))
        .andExpect(jsonPath("$.binding.instanceId").value(instanceId));

    mockMvc.perform(get("/api/admin/wechat-bindings/search")
            .cookie(adminCookie)
            .param("phone", "5728"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bindings.length()").value(1))
        .andExpect(jsonPath("$.bindings[0].accountId").value("wx_existing"))
        .andExpect(jsonPath("$.bindings[0].phone").value("13572873189"));

    mockMvc.perform(post("/api/admin/instances/" + instanceId + "/wechat-accounts/wx_existing/restart-channel")
            .cookie(adminCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.account.instanceId").value(instanceId))
        .andExpect(jsonPath("$.account.accountId").value("wx_existing"))
        .andExpect(jsonPath("$.account.status").value("accepted"));

    mockMvc.perform(post("/api/admin/wechat-accounts/restart-channel")
            .cookie(adminCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "accounts": [
                    { "instanceId": "%s", "accountId": "wx_existing" },
                    { "instanceId": "%s", "accountId": "missing" }
                  ]
                }
                """.formatted(instanceId, instanceId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accounts.length()").value(2))
        .andExpect(jsonPath("$.accounts[0].status").value("accepted"))
        .andExpect(jsonPath("$.accounts[1].status").value("failed"));

    MvcResult existingBindLinkResponse = mockMvc.perform(post("/api/admin/wechat-bind-links")
            .cookie(adminCookie)
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "admin.example.test")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"existing\",\"phone\":\"13572873189\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.mode").value("existing"))
        .andExpect(jsonPath("$.link.modeLabel").value("老用户"))
        .andExpect(jsonPath("$.link.status").value("created"))
        .andExpect(jsonPath("$.link.statusLabel").value("已创建"))
        .andExpect(jsonPath("$.link.phone").value("13572873189"))
        .andExpect(jsonPath("$.link.instanceId").value(instanceId))
        .andExpect(jsonPath("$.link.bindLink").value(org.hamcrest.Matchers.startsWith("https://admin.example.test/bind/")))
        .andReturn();
    String existingBindToken = objectMapper.readTree(existingBindLinkResponse.getResponse().getContentAsString())
        .at("/link/token").asText();
    waitForAdminLinkStatus(adminCookie, existingBindToken, "waiting_scan");

    mockMvc.perform(post("/api/admin/instances/batch/restart-gateway")
            .cookie(adminCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "instanceIds": ["%s", "missing_instance"] }
                """.formatted(instanceId)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.instances.length()").value(2))
        .andExpect(jsonPath("$.instances[0].status").value("accepted"))
        .andExpect(jsonPath("$.instances[1].status").value("failed"));
  }

  private void waitForAdminLinkStatus(Cookie adminCookie, String token, String expectedStatus) throws Exception {
    AssertionError lastError = null;
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      MvcResult result = mockMvc.perform(get("/api/admin/wechat-bind-links/" + token)
              .cookie(adminCookie))
          .andReturn();
      try {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String status = objectMapper.readTree(result.getResponse().getContentAsString())
            .at("/link/status")
            .asText();
        if (expectedStatus.equals(status)) {
          return;
        }
        lastError = new AssertionError("Expected link status " + expectedStatus + " but was " + status);
      } catch (AssertionError error) {
        lastError = error;
      }
      Thread.sleep(100);
    }
    throw lastError == null ? new AssertionError("Timed out waiting for link status " + expectedStatus) : lastError;
  }

  private Cookie loginAdmin() throws Exception {
    MvcResult login = mockMvc.perform(post("/api/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"integration-admin@example.com","password":"Integration123!"}
                """))
        .andReturn();
    if (login.getResponse().getStatus() != 200) {
      login = mockMvc.perform(post("/api/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  {"email":"integration-admin@example.com","password":"Integration456!"}
                  """))
          .andExpect(status().isOk())
          .andReturn();
    }
    Cookie cookie = login.getResponse().getCookie("clawbot_session");
    assertThat(cookie).isNotNull();
    return cookie;
  }
}
