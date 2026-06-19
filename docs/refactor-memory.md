# Claw Manager 重构记忆迁移

更新时间：2026-06-19

本文件迁移自 `clawbot-for-all` 中重构完成后的项目分析与重构计划，只保留对独立仓库 `claw-manager` 仍然有效的上下文。后续若代码与本文冲突，以当前仓库代码和配置为准。

## 1. 来源与边界

- `claw-manager` 是从 `clawbot-for-all` 前后端分离重构成果中拆出的独立 OpenClaw 管理台。
- 旧 Node 单体源码和旧静态前端已移出事实源范围。
- 当前维护边界是 Spring Boot API、Vue Web、OpenClaw runner 镜像、Docker Compose 编排和 GHCR 发布。
- 项目名、镜像名、版本文件已切换到 `claw-manager`；Java package 和部分数据库/配置历史命名仍保留 `clawbot` / `clawbotforall`。

## 2. 当前架构

- 后端：Spring Boot 3 + JDK 21 + MySQL + MyBatis + Spring Security + Redis + docker-java。
- 前端：Vue 3 + Vite + TypeScript + Pinia + Element Plus + STOMP WebSocket。
- 编排：Docker Compose 管理 `mysql`、`redis`、`api`、`web` 四个服务。
- 运行入口：Web 由 Nginx 托管并反代 `/api`、`/ws`、`/proxy` 到 Spring Boot API。
- API 容器需要挂载 `/var/run/docker.sock`，用于创建和管理 OpenClaw 实例容器。
- `./data` 保存实例挂载目录、OpenClaw 配置、workspace 和 server 日志。
- MySQL 是业务状态事实源；Redis 随编排提供，但不作为持久业务状态源。

## 3. 后端模块记忆

- `auth`：Cookie Session、管理员初始化、首次登录改密。
- `model`：模型 Provider、模型预设、默认预设、实例模型链。
- `instance`：OpenClaw 实例创建、启动、停止、Gateway provisioning、模型链、运行统计。
- `wechat`：微信扫码绑定、多账号读取、备注、解绑，数据来源为持久目录中的 `accounts.json`。
- `runtime`：docker-java 封装镜像、容器、exec、logs、stats。
- `proxy`：Control UI HTTP/WebSocket 代理。
- `ws`：STOMP Endpoint、管理员 topic、入站鉴权。

## 4. 前端模块记忆

- `views/LoginView.vue`：登录。
- `views/ChangePasswordView.vue`：账户设置、改密。
- `views/AdminView.vue`：当前主控制台，集中承载概览、实例创建/启停/Gateway 重启、Control UI、微信绑定链接、微信账号备注/解绑、模型预设、runner 镜像、服务日志。
- `views/BindView.vue`：公开微信绑定链接流程。
- `stores/session.ts`：登录会话状态。
- `stores/admin.ts`：后台数据、实例状态、模型配置、微信状态、管理员 topic 事件处理。
- `ws/client.ts`：STOMP 连接、订阅、断线重连后的 REST 校准。

旧重构计划中曾包含普通用户实例页、邀请码注册页、API Token 和独立实例 store；当前独立仓库以前端实际代码为准，主要面向管理员控制台和公开绑定页。

## 5. 数据库记忆

MySQL 由 Flyway 管理建表，不做旧 Node 历史数据迁移。当前核心表包括：

- `admins`：管理员、密码 hash、首次改密标记。
- `admin_sessions`：管理员 Web Cookie Session。
- `model_presets`：模型预设和默认模型。
- `instances`：OpenClaw 实例主记录。
- `instance_models`：实例模型链。
- `instance_provisioning`：实例/Gateway 启动进度和状态。
- `instance_model_auth`：模型交互式授权状态。
- `instance_wechat_binding`：微信绑定运行状态。
- `wechat_paired_accounts`：已配对微信账号和备注。
- `wechat_bind_links`：管理员生成的公开微信绑定链接、手机号、二维码和完成状态。

## 6. 状态推送记忆

REST 负责命令类操作，WebSocket/STOMP 负责管理员状态刷新。消息格式保持类似：

```json
{
  "type": "instance.updated",
  "traceId": "evt_xxx",
  "occurredAt": "2026-06-14T00:00:00.000Z",
  "payload": {}
}
```

管理员 topic：

- `/topic/admin/instances`
- `/topic/admin/instance-stats`
- `/topic/admin/wechat`
- `/topic/admin/model-auth`
- `/topic/admin/runner-image`

事件类型：

- `instance.updated`
- `instance.provisioning.updated`
- `instance.stats.updated`
- `wechat.binding.updated`
- `modelAuth.updated`
- `runnerImage.updated`
- `admin.instances.updated`

约束：

- STOMP CONNECT/SUBSCRIBE/SEND 需要已登录管理员主体。
- `/topic/admin/**` 订阅需要管理员登录态。
- 前端登录成功后建立连接，退出登录时断开并清空 store。
- 前端断线重连后调用管理员 REST 接口做一次全量校准。
- WebSocket 推送不得覆盖表单正在编辑的 draft state。

## 7. 部署与发布记忆

默认端口：

- Web：`4300`
- API：`8080`
- MySQL：`13306`

并行调试端口：

```powershell
$env:API_HOST_PORT='18080'
$env:WEB_HOST_PORT='14300'
docker compose up -d api web
```

生产镜像：

- `ghcr.io/zhangsen540445123/claw-manager-api`
- `ghcr.io/zhangsen540445123/claw-manager-web`
- `ghcr.io/zhangsen540445123/claw-manager-openclaw-runner`

发布记忆：

- 发布镜像支持 `linux/amd64` 和 `linux/arm64`。
- runner 镜像默认组合固定为 `openclaw@2026.6.1` + `@tencent-weixin/openclaw-weixin@2.4.4`。
- `OPENCLAW_GATEWAY_READY_TIMEOUT_MS` 默认 30 分钟，首次启动 OpenClaw 较慢时不要过早判失败。
- `OPENCLAW_MODEL_CONTEXT_WINDOW` 和 `OPENCLAW_MODEL_MAX_TOKENS` 写入 OpenClaw 模型配置。
- `OPENCLAW_CONTROL_UI_ALLOWED_ORIGINS` 必须包含外层 Web 访问来源；默认覆盖 `4300` 和并行调试用 `14300`。
- 每实例容器 CPU/内存可通过 `OPENCLAW_RUNNER_CPUS`、`OPENCLAW_RUNNER_MEMORY` 限制。

## 8. 已继承的真实问题修复

- Gateway 健康检查使用容器网络目标。
- HTTP proxy 固定 HTTP/1.1，避免 Java HttpClient h2c Upgrade 触发 `Invalid Upgrade header`。
- Nginx 仅在真实 Upgrade 时设置 `Connection: upgrade`。
- 实例 `dashboardUrl` 使用 `#token=` fragment 自动注入 Gateway token。
- WebSocket proxy 在握手阶段传递登录用户、拼接上游分片消息，并向上游透传浏览器 `Origin`。
- `/ws` 握手阶段会从 Spring Security 上下文恢复 Principal，登录态浏览器应直接完成 STOMP `CONNECTED`。
- 前端 Gateway 进度文案应与 30 分钟超时窗口一致，表达首次启动可能需要 5-30 分钟。

## 9. 继承验证记录

从旧项目重构完成阶段迁移来的验证记忆：

- `mvn test` 曾覆盖后端单元测试和 MySQL 8.4 + Redis Testcontainers 集成测试。
- `npm run build` 曾通过前端构建。
- 根目录综合检查曾覆盖后端测试、前端构建和 compose config。
- `docker compose build api web`、`docker compose config --quiet`、`actionlint` 曾通过。
- `/actuator/health` 曾返回 `UP`。
- `/v3/api-docs` 曾返回 OpenAPI 3.0.1。
- 管理员登录、会话读取和 STOMP CONNECT smoke test 曾通过。
- 真实实例验证曾确认 `/proxy/{instanceId}/` 返回 OpenClaw Control UI HTML，静态 JS 资源可通过代理下载，WebSocket 代理可收到 Gateway `connect.challenge`，浏览器可从前端进入 OpenClaw Control 应用界面。

这些记录代表迁移前的已知状态；当前仓库变更后仍应以本地重新执行的验证结果为准。

## 10. 待人工验收事项

- 创建真实 OpenClaw 实例。
- 等待 Gateway 在 30 分钟窗口内 ready。
- 打开 Control UI 并确认 HTTP/WebSocket 代理均正常。
- 微信扫码绑定、多账号备注、解绑、重启后凭证恢复。
- 管理员页面和公开绑定页的真实浏览器操作验收。
