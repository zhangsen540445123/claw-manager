# 发布与 CI/CD

本仓库通过 GitHub Actions 做测试、构建和镜像发布。

## CI

Workflow：`.github/workflows/ci.yml`

触发时机：

- Pull Request
- push 到 `main`
- 手动触发 `workflow_dispatch`

执行内容：

| Job | 内容 |
| --- | --- |
| Backend Tests | `backend` 目录执行 `mvn test` |
| Frontend Build | `frontend` 目录执行 `npm ci` 和 `npm run build` |
| Compose Config | 执行 `docker compose config --quiet` 和 `docker compose -f compose.yaml -f compose.local.yaml config --quiet` |

CI 不发布镜像。

## 镜像发布

Workflow：`.github/workflows/publish-images.yml`

触发时机：

- push 到 `main`，且命中指定路径
- 手动触发 `workflow_dispatch`

路径过滤：

| 改动路径 | 构建镜像 |
| --- | --- |
| `claw-manager.version` | API、Web、Runner |
| `backend/**` | API |
| `frontend/**` | Web |
| `containers/openclaw-runner/**` | Runner |

手动触发时会构建全部矩阵镜像。

## 发布镜像

| 镜像 | Dockerfile | Context |
| --- | --- | --- |
| `claw-manager-api` | `backend/Dockerfile` | `backend` |
| `claw-manager-web` | `frontend/Dockerfile` | `frontend` |
| `claw-manager-openclaw-runner` | `containers/openclaw-runner/Dockerfile` | 仓库根目录 |

镜像 tag：

- `claw-manager.version` 中的版本号
- `latest`
- `sha-<commit>`

发布平台：

- `linux/amd64`
- `linux/arm64`

## Runner 构建参数

当前 workflow 中 Runner 使用：

| 变量 | 当前值 |
| --- | --- |
| `RUNNER_OPENCLAW_VERSION` | `2026.6.8` |
| `RUNNER_WECHAT_PLUGIN_SPEC` | `@tencent-weixin/openclaw-weixin@2.4.4` |

后台实际安装微信插件和 OpenViking 插件时，以后台插件管理和预设配置为准。

## 本地发布前检查

```powershell
cd backend
mvn test

cd ..\frontend
npm run build

cd ..
docker compose config --quiet
docker compose -f compose.yaml -f compose.local.yaml config --quiet
```
