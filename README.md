# Claw Manager

Claw Manager 是一个面向管理员的 OpenClaw 管理台，用来统一管理 OpenClaw 实例、模型预设、微信插件、OpenViking 记忆预设、实例运行状态和 Control UI 代理。

## 快速开始

本地使用已发布镜像启动：

```powershell
docker compose up -d
```

访问：

```text
http://127.0.0.1:4300
```

默认端口：

| 服务 | 地址 |
| --- | --- |
| Web | `http://127.0.0.1:4300` |
| API | `http://127.0.0.1:8080` |
| MySQL | `127.0.0.1:13306` |

如果本机端口已被占用：

```powershell
$env:API_HOST_PORT='18080'
$env:WEB_HOST_PORT='14300'
docker compose up -d api web
```

从本地源码构建 API/Web 容器：

```powershell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## 文档入口

仓库级文档从 [docs/README.md](docs/README.md) 开始。

常用入口：

| 主题 | 文档 |
| --- | --- |
| 项目架构 | [docs/architecture.md](docs/architecture.md) |
| Docker 部署 | [docs/deployment.md](docs/deployment.md) |
| 本地开发 | [docs/development.md](docs/development.md) |
| OpenViking 集成 | [docs/openviking.md](docs/openviking.md) |
| 插件体系 | [docs/plugins.md](docs/plugins.md) |
| 发布与 CI/CD | [docs/ci-release.md](docs/ci-release.md) |
| 常见问题 | [docs/troubleshooting.md](docs/troubleshooting.md) |
| 历史记录 | [docs/history/refactor-memory.md](docs/history/refactor-memory.md) |

## 当前事实

- 后端：Spring Boot 3、JDK 21、MySQL、MyBatis、Spring Security、Redis、docker-java。
- 前端：Vue 3、Vite、TypeScript、Pinia、Element Plus、STOMP WebSocket。
- 编排：Docker Compose 管理 MySQL、Redis、API、Web。
- Runner：由 API 通过 Docker socket 创建和管理 OpenClaw 实例容器。
- OpenViking：由后台“OpenViking预设”统一配置，插件按微信发送者派生稳定用户 ID 并隔离记忆。

## 验证

```powershell
cd backend
mvn test

cd ..\frontend
npm run build

cd ..
docker compose config --quiet
docker compose -f compose.yaml -f compose.local.yaml config --quiet
```

## License

MIT
