# OpenViking 集成

Claw Manager 当前采用“后台预设 + 后端确定身份 + broker 代管 user key”的方式接入 OpenViking。所有 OpenClaw 实例共用一个 OpenViking `account_id`，后端使用数据库中的身份盐值生成并保存 `wx_<hash>` 用户 ID；微信通道和小程序 API 通道可以复用同一个 `wx_<hash>`，实现跨通道的用户级记忆隔离。

## 核心模型

| 概念 | 当前约定 |
| --- | --- |
| Account | 当前统一使用 `claw-manager`，不按 OpenClaw 实例拆 account |
| 微信发送者 | 微信插件上报的真实微信用户 ID，后端在绑定完成时用于生成 `wx_<hash>` |
| 小程序用户 | 小程序后端传入的 `openid`，扫码成功后绑定到同一个 `wx_<hash>` |
| 身份盐值 | 后台“OpenViking预设”保存，后端用它生成 `openid_hash` 和 OpenViking 用户 ID |
| OpenViking 用户 ID | 微信/小程序共享 `wx_` + `HMAC_SHA256(identity_salt, trim(wechatUserId)).hex.slice(0, 32)` |
| Root API Key | 只保存在 Claw Manager 后台，用于注册用户和生成 user key |
| User Key | 保存在 `openviking_user_keys`，Runner 插件通过内部 broker 获取 |

## 写入与召回链路

1. 微信扫码绑定完成后，Claw Manager 后端用数据库盐值生成 `wx_<hash>`，并保存到微信绑定或小程序绑定记录。
2. 微信插件和 API Channel 都在会话中传递后端确定的 `openVikingUserId`，并写入 `sessionKey -> openvikingUserId/senderHash` handoff。
3. OpenViking 插件在 assemble、afterTurn 或工具调用中优先读取显式 `openVikingUserId` 或 handoff。
4. 插件通过 Claw Manager 内部 broker 获取当前 `openviking_user_id` 对应的 user key。
5. OpenViking 数据 API 使用 `X-API-Key: <user_key>` 发起请求。
6. 缺少显式身份或 handoff 时跳过用户记忆能力，不从原始 sender 字段兜底生成身份，也不回退默认用户。

小程序 API 聊天的关键点是：聊天请求使用 `Authorization: Bearer cm_user_...` 解析到 `miniapp_user_bindings.openviking_user_id`，然后由后端把这个 `wx_<hash>` 传给 API Channel。API Channel 不创建新的 `api_<hash>` 身份。

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

- 身份盐值上线后不要随意修改，否则同一个微信用户或小程序 openid 会变成新的 hash，旧记忆不会删除，但不会按新 ID 召回。
- Root API Key 不注入 Runner 容器。
- OpenViking 用户记忆读写、召回、注入必须使用 user-scoped user key。
- Control UI 自身对话如果没有显式 `openVikingUserId` 或 handoff，会跳过 OpenViking 用户记忆。

## 相关文档

- 插件包说明：[../plugins/openviking-openclaw-plugin/README_CN.md](../plugins/openviking-openclaw-plugin/README_CN.md)
- 插件安装说明：[../plugins/openviking-openclaw-plugin/INSTALL-ZH.md](../plugins/openviking-openclaw-plugin/INSTALL-ZH.md)
- 二开历史：[history/openviking-openclaw-plugin-fork.md](history/openviking-openclaw-plugin-fork.md)
