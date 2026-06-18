# Claw Manager

OpenClaw 管理台，支持管理员统一维护实例、生成微信扫码绑定链接、预装微信插件、后台镜像预热、服务日志查看，以及基于 GHCR 的镜像部署。发布镜像支持 `linux/amd64` 和 `linux/arm64`。

OpenClaw control plane with admin-managed instances, public WeChat binding links, a preinstalled WeChat plugin, background runner-image warmup, server log viewing, and GHCR-based deployment. Published images support `linux/amd64` and `linux/arm64`.

## 当前源码架构与本地联调

当前重构版源码拆分为：

- `backend/`：Spring Boot 3 + JDK 21 + MySQL + MyBatis + Spring Security + Redis + docker-java。
- `frontend/`：Vue 3 + Vite + TypeScript + Pinia + Element Plus + STOMP WebSocket。
- `compose.yaml`：本地编排 `mysql`、`redis`、`api`、`web` 四个服务。

默认端口：

- Web：`http://127.0.0.1:4300`
- API：`http://127.0.0.1:8080`
- MySQL：`127.0.0.1:13306`

如果旧服务已经占用 `4300` 或 `8080`，可以用替代端口并行启动新架构：

```powershell
$env:API_HOST_PORT='18080'
$env:WEB_HOST_PORT='14300'
docker compose up -d api web
```

启动后访问 `http://127.0.0.1:14300`，API 健康检查为 `http://127.0.0.1:18080/api/health`。

常用验证命令：

```powershell
cd backend
mvn test

cd ..\frontend
npm run build

cd ..
docker compose build api web
```

## 1. 功能列表

### 中文

- 管理员初始化、登录会话和首次登录强制改密
- MySQL 存储管理员会话、模型预设、实例状态、微信绑定链接和手机号-微信-实例映射
- OpenClaw 实例启动、停止、Gateway 重启和维护操作统一收编到管理员后台
- 管理员可为新用户生成扫码链接，用户先填写手机号再扫码；老用户按手机号回到原实例扫码
- 微信用户唯一性以 `accounts.json` 中的账号标识为准，一个微信账号和一个手机号只绑定一个 OpenClaw 实例
- Runner 镜像预装微信插件，创建实例后可直接拉起二维码绑定
- Server 启动后后台预热 runner 镜像，不阻塞服务启动
- 管理员后台可查看 runner 镜像状态、服务日志、实例状态、模型预设和每个实例关联的微信账号
- 实例容器支持通过环境变量限制 CPU 和内存，便于单机部署多个实例
- API / Web / Runner 镜像通过 GitHub Actions 发布到 GHCR
- 发布镜像支持 `linux/amd64` 与 `linux/arm64`
- Runner 镜像默认钉死已验证组合：`openclaw@2026.6.1` + `@tencent-weixin/openclaw-weixin@2.4.4`

### English

- Admin bootstrap, admin sessions, and forced password change on first login
- MySQL-backed admin sessions, model presets, instance state, WeChat binding links, and phone-WeChat-instance mappings
- Instance lifecycle and maintenance actions are handled from the admin console
- Admins can create new-user binding links with phone collection, or existing-user links that route users back to their original instance
- WeChat identity is derived from the account IDs stored in `accounts.json`
- Runner image ships with the WeChat plugin preinstalled for immediate QR pairing
- Server warms the runner image in the background after startup without blocking HTTP boot
- Admin console can inspect runner image status, server logs, instance state, model presets, and linked WeChat accounts
- Per-instance container CPU and memory limits can be configured with environment variables
- API, web, and runner images are published to GHCR via GitHub Actions
- Published images support both `linux/amd64` and `linux/arm64`
- The runner image is pinned to a verified pairing: `openclaw@2026.6.1` + `@tencent-weixin/openclaw-weixin@2.4.4`

## 2. 如何快速 Docker 部署

### 中文

1. 准备目录：

```bash
mkdir -p /opt/claw-manager
cd /opt/claw-manager
```

2. 写入 `compose.yaml`：

```yaml
services:
  mysql:
    image: mysql:8.4
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ChangeRootPassword!
      MYSQL_DATABASE: clawbot
      MYSQL_USER: clawbot
      MYSQL_PASSWORD: ChangeDbPassword!
    ports:
      - "13306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uclawbot -pChangeDbPassword! --silent"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 30s

  redis:
    image: redis:7.4-alpine
    restart: unless-stopped
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 30

  api:
    image: ghcr.io/zhangsen540445123/claw-manager-api:latest
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      API_PORT: 8080
      MYSQL_HOST: mysql
      MYSQL_PORT: 3306
      MYSQL_DATABASE: clawbot
      MYSQL_USER: clawbot
      MYSQL_PASSWORD: ChangeDbPassword!
      REDIS_HOST: redis
      REDIS_PORT: 6379
      SESSION_TTL_DAYS: 14
      ADMIN_EMAIL: admin@example.com
      ADMIN_NAME: 平台管理员
      ADMIN_PASSWORD: ChangeMe123!
      OPENCLAW_RUNNER_IMAGE: ghcr.io/zhangsen540445123/claw-manager-openclaw-runner:latest
      OPENCLAW_RUNNER_PULL_TIMEOUT_MS: 600000
      OPENCLAW_RUNNER_CPUS: "1.0"
      OPENCLAW_RUNNER_MEMORY: 1g
      OPENCLAW_WECHAT_BIND_TIMEOUT_MS: 600000
      OPENCLAW_GATEWAY_READY_TIMEOUT_MS: 1800000
      OPENCLAW_GATEWAY_READY_CHECK_INTERVAL_MS: 10000
      OPENCLAW_GATEWAY_READY_PROBE_TIMEOUT_MS: 5000
      OPENCLAW_MODEL_CONTEXT_WINDOW: 1000000
      OPENCLAW_MODEL_MAX_TOKENS: 128000
      OPENCLAW_CONTROL_UI_ALLOWED_ORIGINS: http://localhost:4300,http://127.0.0.1:4300
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock

  web:
    image: ghcr.io/zhangsen540445123/claw-manager-web:latest
    restart: unless-stopped
    depends_on:
      api:
        condition: service_started
    ports:
      - "4300:80"

volumes:
  mysql-data:
  redis-data:
```

3. 启动：

```bash
docker compose up -d
```

4. 访问和日志：

```text
http://127.0.0.1:4300
```

```bash
docker logs -f <api-container-name>
```

说明：

- Web 容器内置 Nginx，反代 `/api`、`/ws`、`/proxy` 到 `api:8080`
- `OPENCLAW_GATEWAY_READY_TIMEOUT_MS` 默认 30 分钟，首次启动 OpenClaw 较慢时不要过早失败
- `OPENCLAW_MODEL_CONTEXT_WINDOW` 和 `OPENCLAW_MODEL_MAX_TOKENS` 分别控制写入 OpenClaw 模型配置的上下文窗口和最大输出 token 数
- `OPENCLAW_CONTROL_UI_ALLOWED_ORIGINS` 用逗号分隔外层 Web 访问来源；如果改了 Web 端口或域名，需要同步加入这里
- MySQL 客户端可连接宿主机 `13306` 端口，账号为 `MYSQL_USER` / `MYSQL_PASSWORD`
- `./data` 保存实例挂载目录、OpenClaw 配置、workspace 和 server 日志
- API 容器必须挂载 `/var/run/docker.sock` 才能创建和管理 OpenClaw 实例容器
- Runner 镜像默认组合：`openclaw@2026.6.1` + `@tencent-weixin/openclaw-weixin@2.4.4`

### English

The production deployment is split into four services: MySQL, Redis, Spring Boot API, and Vue/Nginx web.

Use the same `compose.yaml` shape shown above. The web container exposes `4300:80`, proxies `/api`, `/ws`, and `/proxy` to `api:8080`, and the API container must mount `/var/run/docker.sock` so it can manage OpenClaw runtime containers.

## 3. 本地开发步骤

### 中文

1. 启动基础服务：

```bash
docker compose up -d mysql redis
```

2. 启动后端：

```bash
cd backend
mvn spring-boot:run
```

3. 启动前端：

```bash
cd frontend
npm ci
npm run dev
```

4. 访问：

```text
http://127.0.0.1:5173
```

本地开发要求：

- JDK 21
- Node.js 22+
- Docker Desktop / Docker Engine
- 当前机器允许执行 `docker pull`、`docker run`、`docker rm`、`docker exec`、`docker logs`

### English

For local development, start MySQL and Redis with Docker Compose, run the Spring Boot API from `backend/`, and run the Vite dev server from `frontend/`.

```bash
docker compose up -d mysql redis
cd backend && mvn spring-boot:run
cd frontend && npm ci && npm run dev
```

Open `http://127.0.0.1:5173`.

## 4. License

MIT

## 5. 感谢

[LinuxDo社区](https://linux.do/)
