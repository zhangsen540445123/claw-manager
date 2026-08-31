# Docker 部署

默认部署使用 `compose.yaml`，直接拉取已发布镜像，不从本地源码构建。

## 启动

```powershell
docker compose up -d
```

访问：

```text
http://127.0.0.1:4300
```

默认服务：

| 服务 | 默认地址 |
| --- | --- |
| Web | `http://127.0.0.1:4300` |
| API | `http://127.0.0.1:8080` |
| MySQL | `127.0.0.1:13306` |

## 常用环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `API_HOST_PORT` | `8080` | API 映射到宿主机的端口 |
| `WEB_HOST_PORT` | `4300` | Web 映射到宿主机的端口 |
| `MYSQL_HOST_PORT` | `13306` | MySQL 映射到宿主机的端口 |
| `ADMIN_EMAIL` | `admin@example.com` | 初始管理员邮箱 |
| `ADMIN_NAME` | `平台管理员` | 初始管理员名称 |
| `ADMIN_PASSWORD` | `ChangeMe123!` | 初始管理员密码 |
| `OPENCLAW_RUNNER_IMAGE` | GHCR latest runner | 创建实例时使用的 Runner 镜像 |
| `OPENCLAW_RUNNER_CPUS` | `1.0` | 单个 Runner 容器 CPU 限制 |
| `OPENCLAW_RUNNER_MEMORY` | `1g` | 单个 Runner 容器内存限制 |
| `OPENCLAW_GATEWAY_READY_TIMEOUT_MS` | `1800000` | Gateway ready 等待窗口，默认 30 分钟 |
| `OPENCLAW_CONTROL_UI_ALLOWED_ORIGINS` | `*` | Control UI 允许的来源；默认允许任意 Origin |
| `OPENCLAW_AGENT_HEARTBEAT_ENABLED` | `false` | OpenClaw Agent Heartbeat 默认关闭；不影响 API 队列 monitor 和 SSE 保活 |
| `OPENCLAW_AGENT_HEARTBEAT_EVERY` | `30m` | 仅显式启用 Agent Heartbeat 时使用的周期 |
| `OPENCLAW_AGENT_HEARTBEAT_ISOLATED_SESSION` | `true` | Heartbeat 必须使用独立 Session，不能复用微信/API 主会话 |
| `OPENCLAW_AGENT_HEARTBEAT_LIGHT_CONTEXT` | `true` | Heartbeat 使用轻量上下文 |
| `OPENCLAW_AGENT_HEARTBEAT_DIRECT_POLICY` | `block` | 禁止 Heartbeat 结果直接投递到微信/API 用户 |

OpenViking 配置不写入 `compose.yaml`，统一在管理员后台“OpenViking预设”中管理。


## Agent Heartbeat 与定时任务

默认关闭的是会调用 Agent/模型的 OpenClaw Agent Heartbeat。API Channel 队列 monitor heartbeat 和 SSE 长连接保活仍保持启用，它们不会进入用户 Session。

默认生成的 `openclaw.json` 包含：

```json
{
  "agents": {
    "defaults": {
      "heartbeat": {
        "every": "0m",
        "isolatedSession": true,
        "lightContext": true,
        "includeSystemPromptSection": false,
        "target": "none",
        "directPolicy": "block",
        "ackMaxChars": 300
      }
    }
  }
}
```

Cron 定时任务与 Agent Heartbeat 是两套机制。关闭 Agent Heartbeat 不会删除 Cron 任务，但启用且配置为 `wakeMode=next-heartbeat` 的 Cron 任务可能无法再被唤醒；部署前应使用后端只读扫描器检查这类依赖并单独调整 Cron 任务。

修改 Heartbeat 环境变量后需要重新创建 API 容器。已有实例还需要重新生成 `openclaw.json` 并重启 Gateway，旧配置才会被覆盖。不要只执行 `docker compose restart api`。API 启动后会延迟执行一次只读扫描，随后默认每 5 分钟扫描一次；扫描只生成脱敏摘要，不会自动删除或轮换 Session，也不会修改 Cron 任务。

## 并行端口

旧服务占用默认端口时，可临时改端口：

```powershell
$env:API_HOST_PORT='18080'
$env:WEB_HOST_PORT='14300'
docker compose up -d api web
```

此时访问 `http://127.0.0.1:14300`。

## 数据目录

API 容器挂载：

- `./data:/app/data`
- `/var/run/docker.sock:/var/run/docker.sock`

`./data` 保存实例挂载目录、OpenClaw 配置、workspace 和服务日志。API 必须挂载 Docker socket 才能创建和管理 OpenClaw Runner 容器。

## 验证

```powershell
docker compose config --quiet
docker compose ps
docker logs -f claw-manager-api
```
