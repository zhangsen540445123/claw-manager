package com.clawbotforall.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.clawbotforall.runtime.RuntimeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
            .content("{\"mode\":\"new\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.mode").value("new"))
        .andExpect(jsonPath("$.link.modeLabel").value("新用户"))
        .andExpect(jsonPath("$.link.status").value("phone_required"))
        .andExpect(jsonPath("$.link.statusLabel").value("待填写手机号"))
        .andExpect(jsonPath("$.link.expiresAt").exists())
        .andExpect(jsonPath("$.link.bindLink").value(org.hamcrest.Matchers.startsWith("https://admin.example.test/bind/")))
        .andReturn();
    String newBindToken = objectMapper.readTree(newBindLinkResponse.getResponse().getContentAsString())
        .at("/link/token").asText();

    mockMvc.perform(get("/api/public/wechat-bind-links/" + newBindToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.status").value("phone_required"))
        .andExpect(jsonPath("$.link.message").value("请先填写手机号获取微信扫码二维码。"));

    mockMvc.perform(get("/api/admin/wechat-bind-links")
            .cookie(adminCookie)
            .param("mode", "new")
            .param("status", "phone_required")
            .param("page", "1")
            .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.links[0].statusLabel").value("待填写手机号"));

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

    mockMvc.perform(post("/api/admin/wechat-bind-links")
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
        .andExpect(jsonPath("$.link.bindLink").value(org.hamcrest.Matchers.startsWith("https://admin.example.test/bind/")));
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
