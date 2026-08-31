# Claw Manager Project Memory

更新时间：2026-06-19

本项目是从 `D:\code\daxiangmu\clawbot-for-all` 拆出的独立 OpenClaw 管理台。旧项目重构后的相关事实已迁移到这里，后续开发以本仓库为事实源。

## 当前事实源

- 项目名和镜像名已经迁移为 `claw-manager`、`claw-manager-api`、`claw-manager-web`、`claw-manager-openclaw-runner`。
- 当前源码入口是 `backend/`、`frontend/`、`containers/openclaw-runner/`、`compose.yaml`、`compose.local.yaml`、`.github/workflows/`。
- Java package、部分配置 key、数据库名仍保留 `clawbot` / `com.clawbotforall` 历史命名；不要为了美观顺手重命名，除非任务明确要求并覆盖全链路迁移。
- 旧 Node 单体和旧静态前端不是本项目事实源。

## 技术栈

- 后端：Spring Boot 3 + JDK 21 + MySQL + MyBatis + Spring Security + Redis + docker-java。
- 前端：Vue 3 + Vite + TypeScript + Pinia + Element Plus + STOMP WebSocket。
- 编排：Docker Compose 管理 `mysql`、`redis`、`api`、`web`。
- Web 容器使用 Nginx 托管静态资源，并反代 `/api`、`/ws`、`/proxy` 到 API。

## 核心行为

- REST 负责命令类操作：管理员登录、改密、创建/启动/停止实例、重启 Gateway、微信绑定链接、微信账号备注/解绑、模型配置、管理员操作。
- WebSocket/STOMP 负责状态刷新；前端初始加载和断线重连后通过 REST 做一次全量校准。
- STOMP CONNECT/SUBSCRIBE/SEND 均需要已登录管理员主体；`/topic/admin/**` 订阅只允许管理员登录态。
- MySQL 是业务状态事实源；Redis 随编排提供，但不要把它当作持久业务状态源。

## 重要模块

- `auth`：Cookie Session、管理员初始化、首次登录强制改密。
- `model`：模型 Provider、模型预设、默认预设、实例模型链。
- `instance`：OpenClaw 实例创建、启动、停止、Gateway provisioning、模型链、运行统计。
- `wechat`：微信扫码绑定、多账号读取、备注、解绑，数据来源为实例持久目录中的 `accounts.json`。
- `runtime`：docker-java 封装镜像、容器、exec、logs、stats。
- `proxy`：OpenClaw Control UI HTTP/WebSocket 代理。
- `ws`：STOMP Endpoint、管理员 topic、入站鉴权。

## WebSocket 约定

管理员 topic：

- `/topic/admin/instances`
- `/topic/admin/instance-stats`
- `/topic/admin/wechat`
- `/topic/admin/model-auth`
- `/topic/admin/runner-image`

常见事件类型：

- `instance.updated`
- `instance.provisioning.updated`
- `instance.stats.updated`
- `wechat.binding.updated`
- `modelAuth.updated`
- `admin.instances.updated`
- `runnerImage.updated`

## 运行与验证

默认端口：

- Web：`http://127.0.0.1:4300`
- API：`http://127.0.0.1:8080`
- MySQL：`127.0.0.1:13306`

旧服务占用端口时可并行使用：

```powershell
$env:API_HOST_PORT='18080'
$env:WEB_HOST_PORT='14300'
docker compose up -d api web
```

常用验证：

```powershell
cd backend
mvn test

cd ..\frontend
npm run build

cd ..
docker compose config --quiet
docker compose -f compose.yaml -f compose.local.yaml config --quiet
```

如需从本地源码构建容器：

```powershell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## 继承自重构验证的关键记忆

- Runner 镜像默认组合：`openclaw@2026.6.8` + `@tencent-weixin/openclaw-weixin@2.4.4`。
- Gateway 首次 ready 窗口按 30 分钟设计，前端文案应表达首次启动可能需要 5-30 分钟。
- Control UI 代理曾修复过以下真实问题：Gateway 健康检查使用容器网络目标；HTTP proxy 固定 HTTP/1.1；Nginx 仅真实 Upgrade 时设置 `Connection: upgrade`；`dashboardUrl` 使用 `#token=` fragment 注入 Gateway token；WebSocket proxy 握手传递登录用户、拼接上游分片消息，并透传浏览器 `Origin`。
- `/ws` 握手阶段需要从 Spring Security 上下文恢复 Principal，登录态浏览器应直接完成 STOMP `CONNECTED`，前端显示实时状态。
- `OPENCLAW_CONTROL_UI_ALLOWED_ORIGINS` 未显式设置时默认使用 `*`，即 Control UI 允许任意 Origin；生产环境可通过环境变量收紧来源范围。
- OpenClaw Agent Heartbeat 默认关闭（`every: "0m"`）；如显式启用，必须使用独立 `:heartbeat` Session、轻量上下文和 `directPolicy: "block"`。API Channel monitor heartbeat 与 SSE 保活不受影响。
- Heartbeat 与 Cron 定时任务必须分开处理；普通用户合法的 `2018`、`-1`、`HEARTBEAT_OK` 不允许被全局过滤。历史混合 Session 优先通过 `/new` 或官方 `sessions.reset` 轮换，不直接删除普通 Session，也不删除 OpenViking 服务端记忆。
- 真实实例验证曾确认 `/proxy/{instanceId}/` 可返回 OpenClaw Control UI HTML，静态 JS 资源可通过代理下载，WebSocket 代理可收到 Gateway `connect.challenge`。

更完整的迁移记录见 `docs/history/refactor-memory.md`。
