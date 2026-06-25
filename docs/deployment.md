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
| `OPENCLAW_CONTROL_UI_ALLOWED_ORIGINS` | 覆盖 `4300` 和 `14300` | Control UI 允许的外层 Web 来源 |

OpenViking 配置不写入 `compose.yaml`，统一在管理员后台“OpenViking预设”中管理。

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
