# 插件体系

Claw Manager 通过后台插件管理为 OpenClaw 实例安装插件。当前重点插件是微信、API Channel、OpenViking 和小程序 Bridge，源码都维护在本仓库 `plugins/` 目录。

## 当前插件

| 插件 | 包名 | OpenClaw 插件 ID | 说明 |
| --- | --- | --- | --- |
| 微信插件 | `@claw-manager/openclaw-weixin` | `openclaw-weixin` | 基于官方微信插件二开，补充 sender 身份传递和 OpenViking handoff |
| API Channel 插件 | `@claw-manager/openclaw-api-channel` | `claw-manager-api` | 接收 Claw Manager 外部聊天请求，转发后端确定的 `openVikingUserId`，写 handoff 并输出 SSE delta |
| OpenViking 插件 | `@claw-manager/openviking-openclaw-plugin` | `openviking` | 基于 OpenViking 官方 OpenClaw 示例插件二开，接入 broker 和用户级记忆隔离 |
| 小程序 Bridge 插件 | `@claw-manager/miniapp-bridge-plugin` | `miniapp-bridge` | 在单个插件中注册待办、目标、子任务、习惯打卡、HTML 五个 sender-scoped 强类型工具，通过 Claw Manager 安全调用当前用户的小程序业务接口 |

## 安装方式

管理员在后台插件管理中安装、检测、升级、重新安装和卸载插件。后台安装命令最终会进入目标 Runner 容器执行 OpenClaw 插件安装。

同一个 OpenClaw 实例中的插件不能同时执行安装、升级、重装或卸载任务；后台串行化这些变更，避免 OpenClaw CLI 插件操作互相冲突。

## 文档边界

仓库级文档说明 Claw Manager 如何管理插件；插件包自己的完整说明保留在插件目录：

- 微信插件中文文档：[../plugins/openclaw-weixin-plugin/README.zh_CN.md](../plugins/openclaw-weixin-plugin/README.zh_CN.md)
- 微信插件变更记录：[../plugins/openclaw-weixin-plugin/CHANGELOG.zh_CN.md](../plugins/openclaw-weixin-plugin/CHANGELOG.zh_CN.md)
- API Channel 插件文档：[../plugins/openclaw-api-channel-plugin/README.md](../plugins/openclaw-api-channel-plugin/README.md)
- OpenViking 插件中文文档：[../plugins/openviking-openclaw-plugin/README_CN.md](../plugins/openviking-openclaw-plugin/README_CN.md)
- OpenViking 插件安装文档：[../plugins/openviking-openclaw-plugin/INSTALL-ZH.md](../plugins/openviking-openclaw-plugin/INSTALL-ZH.md)
- 小程序 Bridge 插件文档：[../plugins/miniapp-bridge-plugin/README.md](../plugins/miniapp-bridge-plugin/README.md)

## 二开约定

- 保留官方插件 ID，避免破坏 OpenClaw 已有状态目录和绑定逻辑。
- npm 包名使用 `@claw-manager/*`。
- 插件包版本使用日期式版本号，例如 `2026.6.30`。
- 微信 sender 原始 ID 不写入普通日志；需要排障时只输出派生 hash 或是否存在。
- API Channel 插件必须使用后端传入的 `openVikingUserId`。小程序聊天会传入扫码绑定得到的 `wx_<hash>`；插件不能自行派生新的 API 用户身份。
- API Channel 插件负责把 OpenClaw assistant 增量输出写入 `.openclaw/claw-manager-api/streams/{requestId}.jsonl`，并把最终结果写入 `responses/{requestId}.json`，后端据此转发 SSE。
- OpenViking 插件缺少显式身份或 handoff 时必须跳过用户记忆能力，不能回退默认用户。
- 小程序 Bridge 只接受固定 `actionKey` 和业务参数。openid、`cm_user_...` 和目标 URL 必须由 Claw Manager 根据 `requesterSenderId` 解析，模型和 Skill 不得提供或覆盖。
