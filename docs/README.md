# Claw Manager 文档

这里是仓库级文档入口。根目录 `README.md` 只保留快速开始和导航；具体部署、开发、OpenViking、小程序接入、插件和发布说明都从这里进入。

## 推荐阅读顺序

1. [项目架构](architecture.md)：先了解 API、Web、Runner、MySQL、Redis 和 OpenClaw Gateway 的职责边界。
2. [Docker 部署](deployment.md)：用已发布镜像启动完整服务。
3. [本地开发](development.md)：用本地源码启动后端、前端和本地构建镜像。
4. [OpenViking 集成](openviking.md)：理解 OpenViking预设、身份盐值、用户 ID、user key 托管和记忆隔离。
5. [小程序接入](miniapp-integration.md)：理解小程序 `openid`、微信扫码绑定、`cm_user_...` key 和 API/微信共享 OpenViking 记忆。
6. [插件体系](plugins.md)：理解微信、API Channel 和 OpenViking 插件的二开包、安装方式和文档边界。
7. [发布与 CI/CD](ci-release.md)：了解 GitHub Actions 何时测试、构建和发布镜像。
8. [常见问题与排障](troubleshooting.md)：遇到 Gateway、微信、小程序、OpenViking、Control UI 问题时先看这里。

## 文档边界

| 类型 | 位置 | 用途 |
| --- | --- | --- |
| 仓库级文档 | `docs/*.md` | 给 Claw Manager 维护者和部署者阅读 |
| 历史记录 | `docs/history/*.md` | 保留迁移、二开过程和历史事实 |
| 小程序接入文档 | `docs/miniapp-integration.md` | 给小程序后端接入方和 Claw Manager 维护者阅读 |
| OpenViking 插件包文档 | `plugins/openviking-openclaw-plugin/README*.md`、`INSTALL*.md` | 给 npm 插件包使用者阅读 |
| 微信插件包文档 | `plugins/openclaw-weixin-plugin/README*.md`、`CHANGELOG*.md` | 给 npm 插件包使用者阅读 |

仓库级文档只说明 Claw Manager 如何使用这些插件；插件自身的完整安装、命令和包级细节保留在插件目录。

## 历史文档

- [重构记忆迁移](history/refactor-memory.md)
- [OpenViking OpenClaw 插件二开说明](history/openviking-openclaw-plugin-fork.md)

历史文档用于追溯上下文。若历史文档与当前代码或当前仓库级文档冲突，以当前代码和当前仓库级文档为准。
