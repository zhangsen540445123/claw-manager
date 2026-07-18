# OpenClaw 配置证据

当前 Runner 固定使用 `openclaw@2026.6.8`。修改 `openclaw.json` 前，必须以该 npm 包内的 `package/docs` 和 `package/dist` 为准；本机其他版本只能用于辅助阅读。

| 配置 | 官方文档依据 | 用途 |
| --- | --- | --- |
| `plugins.slots.memory = "none"` | `docs/gateway/configuration-reference.md` | 禁用 memory plugin |
| `plugins.slots.contextEngine` | `docs/gateway/configuration-reference.md`、`docs/concepts/context-engine.md` | 选择 OpenViking context engine |
| `agents.defaults.skipBootstrap` | `docs/gateway/config-agents.md`、`docs/cli/onboard.md` | 由 Claw Manager 管理初始化文件 |
| `agents.defaults.compaction.memoryFlush.enabled` | `docs/gateway/config-agents.md` | 关闭本地 memory flush |
| `session.dmScope` | `docs/channels/wechat.md`、`docs/gateway/configuration.md` | 按账号、渠道和发送者隔离 DM |
| `agents.list[]`、`workspace`、`agentDir`、`bindings[]` | `docs/concepts/multi-agent.md` | 按用户创建独立 Agent |
| `agents.list[].tools.deny` | `docs/tools/multi-agent-sandbox-tools.md` | 禁止绕过 workspace 边界的原生工具 |
| `plugins.allow`、`plugins.entries.workspace-file.enabled` | `docs/plugins/building-plugins.md`、`docs/plugins/architecture-internals.md` | 启用随 Runner 安装的 `workspace-file` 工具插件 |

部署验证必须在目标 Runner 执行：

```bash
openclaw config validate --json
openclaw doctor --lint --json
openclaw agents list --bindings
```

官方在线文档：

- https://docs.openclaw.ai/gateway/configuration-reference
- https://docs.openclaw.ai/gateway/config-agents
- https://docs.openclaw.ai/concepts/multi-agent
- https://docs.openclaw.ai/tools/multi-agent-sandbox-tools
- https://docs.openclaw.ai/channels/wechat
- https://docs.openclaw.ai/plugins/building-plugins
- https://docs.openclaw.ai/plugins/hooks
