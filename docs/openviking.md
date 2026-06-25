# OpenViking 集成

Claw Manager 当前采用“后台预设 + broker 代管 user key”的方式接入 OpenViking。所有 OpenClaw 实例共用一个 OpenViking `account_id`，每个微信真实发送者派生稳定的 OpenViking 用户 ID，实现用户级记忆隔离。

## 核心模型

| 概念 | 当前约定 |
| --- | --- |
| Account | 当前统一使用 `claw-manager`，不按 OpenClaw 实例拆 account |
| 微信发送者 | 来自微信插件的 `full.from_user_id` |
| 身份盐值 | 后台“OpenViking预设”保存，用于 HMAC 派生用户 ID |
| OpenViking 用户 ID | `wx_` + `HMAC_SHA256(identity_salt, trim(senderId)).hex.slice(0, 32)` |
| Root API Key | 只保存在 Claw Manager 后台，用于注册用户和生成 user key |
| User Key | 保存在 `openviking_user_keys`，Runner 插件通过内部 broker 获取 |

## 写入与召回链路

1. 微信消息进入二开微信插件。
2. 微信插件从 `full.from_user_id` 取发送者身份，并写入 `SenderId`、`senderId`、`requesterSenderId`。
3. 微信插件同时写入 handoff 文件，保存 `sessionKey -> openvikingUserId/senderHash`，不保存原始微信 ID。
4. OpenViking 插件在 assemble、afterTurn 或工具调用中解析 sender 身份。
5. 插件通过 Claw Manager 内部 broker 获取当前 `openviking_user_id` 对应的 user key。
6. OpenViking 数据 API 使用 `X-API-Key: <user_key>` 发起请求。
7. 缺少 sender 身份时跳过用户记忆能力，不回退默认用户。

## OpenViking预设

后台页面负责管理：

- `OPENVIKING_BASE_URL`：OpenViking 服务地址。
- Trusted Mode：页面默认开启；当前实际数据 API 走 user key 模式。
- `accountId`：默认 `claw-manager`。
- 身份盐值：用于派生 `wx_<hash>`，修改后同一微信用户会映射到新的 OpenViking 用户 ID。
- Root API Key：只保存和显示指纹，不回显原文。
- 插件包：默认安装 `@claw-manager/openviking-openclaw-plugin` 的指定版本。

为了兼容已发布插件，Runner 环境变量仍使用 `OPENVIKING_IDENTITY_HASH_SECRET`，但其值来自后台“身份盐值”。

## 重要注意

- 身份盐值上线后不要随意修改，否则同一个微信用户会变成新的 OpenViking 用户 ID，旧记忆不会删除，但不会按新 ID 召回。
- Root API Key 不注入 Runner 容器。
- OpenViking 用户记忆读写、召回、注入必须使用 sender-scoped user key。
- Control UI 自身对话如果没有微信 sender 身份，会跳过 OpenViking 用户记忆。

## 相关文档

- 插件包说明：[../plugins/openviking-openclaw-plugin/README_CN.md](../plugins/openviking-openclaw-plugin/README_CN.md)
- 插件安装说明：[../plugins/openviking-openclaw-plugin/INSTALL-ZH.md](../plugins/openviking-openclaw-plugin/INSTALL-ZH.md)
- 二开历史：[history/openviking-openclaw-plugin-fork.md](history/openviking-openclaw-plugin-fork.md)
