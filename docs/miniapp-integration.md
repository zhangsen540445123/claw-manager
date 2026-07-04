# 小程序接入说明

本文面向微信小程序后端服务。小程序前端只需要拿到二维码和用户 key，所有与 Claw Manager 的服务端鉴权、出码、生成 key 和聊天请求都应由小程序后端完成。

## 接入目标

小程序用户以 `openid` 作为唯一身份。用户先通过 Claw Manager 生成的微信二维码完成扫码绑定，绑定后得到一个 `cm_user_...` 用户 key。之后小程序后端使用这个 key 调用聊天接口，API 通道和微信通道共享同一个 OpenViking `wx_<hash>` 用户记忆。

核心链路：

```text
小程序 openid
  -> 微信扫码绑定
  -> miniapp_user_bindings.openviking_user_id = wx_<hash>
  -> cm_user_... 用户 key
  -> /api/external/openclaw/chat/stream
  -> API Channel handoff
  -> OpenViking wx_<hash> 用户记忆
```

## 前置条件

- 至少一个 OpenClaw 实例处于 `running`，且 `instance_provisioning.status=ready`。
- 目标实例已安装并加载 API Channel、微信插件和 OpenViking 插件。
- 后台“OpenViking预设”已配置 base URL、accountId、身份盐值和 Root API Key。
- `miniapp_clients` 中已创建小程序后端调用方，包含 `app_id` 和 `app_secret`。
- 小程序后端能够保存 `cm_user_...` key，并在后续请求中使用它作为用户聊天凭据。

## 鉴权模型

小程序管理类接口使用 HMAC 鉴权。聊天接口使用用户 key 鉴权，不使用旧外部聊天共享凭据。

### HMAC 请求头

| Header | 说明 |
| --- | --- |
| `X-CM-App-Id` | `miniapp_clients.app_id` |
| `X-CM-Timestamp` | 当前毫秒时间戳，允许 5 分钟时钟偏移 |
| `X-CM-Nonce` | 每次请求唯一随机值，5 分钟内不可重复 |
| `X-CM-Signature` | HMAC-SHA256 十六进制签名 |

### 签名串

```text
METHOD
PATH_WITH_QUERY
X-CM-TIMESTAMP
X-CM-NONCE
SHA256(rawBody)
```

- `METHOD` 使用大写，例如 `POST`。
- `PATH_WITH_QUERY` 只包含路径和 query，例如 `/api/external/miniapp/wechat-bind-links`。
- `rawBody` 必须是实际发送的原始 body 字符串，GET 请求使用空字符串。
- HMAC secret 使用 `miniapp_clients.app_secret`。

Node.js 示例：

```js
import crypto from "node:crypto";

function sign({ secret, method, pathWithQuery, timestamp, nonce, rawBody }) {
  const bodyHash = crypto.createHash("sha256").update(rawBody || "", "utf8").digest("hex");
  const canonical = [
    method.toUpperCase(),
    pathWithQuery,
    timestamp,
    nonce,
    bodyHash,
  ].join("\n");
  return crypto.createHmac("sha256", secret).update(canonical, "utf8").digest("hex");
}
```

## 接口定义

### 创建微信绑定二维码

```http
POST /api/external/miniapp/wechat-bind-links
Content-Type: application/json
X-CM-App-Id: miniapp_main
X-CM-Timestamp: 1783160000000
X-CM-Nonce: 8f1d1f32-6c7d-4b7e-b9e4-001
X-CM-Signature: <hex>
```

```json
{
  "openid": "miniapp-openid-001"
}
```

响应：

```json
{
  "binding": {
    "openid": "miniapp-openid-001",
    "bindToken": "wbl_xxx",
    "status": "waiting_scan",
    "instanceId": "mr67mzy8-30acf3",
    "openVikingUserId": "",
    "canCreateUserKey": false,
    "qrLink": "https://liteapp.weixin.qq.com/q/...",
    "qrPayload": "",
    "expiresAt": "2026-07-05T10:30:10Z"
  }
}
```

行为说明：

- 首次请求会根据实例负载选择一个 OpenClaw 实例，并写入 `miniapp_user_bindings`。
- 当前“负载最小”按 `微信绑定用户数 + 旧 API 用户数 + 小程序绑定用户数` 计算。
- 同一 `openid` 二次出码会复用原 `instance_id`，不会改变已绑定的 `openviking_user_id`。

### 查询绑定状态

```http
GET /api/external/miniapp/wechat-bind-links/{bindToken}
X-CM-App-Id: miniapp_main
X-CM-Timestamp: 1783160000000
X-CM-Nonce: 8f1d1f32-6c7d-4b7e-b9e4-002
X-CM-Signature: <hex>
```

扫码完成后响应：

```json
{
  "binding": {
    "openid": "miniapp-openid-001",
    "bindToken": "wbl_xxx",
    "status": "connected",
    "instanceId": "mr67mzy8-30acf3",
    "openVikingUserId": "wx_8db1ee8f655145d6cfa4e286cda3fda3",
    "canCreateUserKey": true,
    "qrLink": "",
    "qrPayload": "",
    "expiresAt": "2026-07-05T10:30:10Z"
  }
}
```

### 生成或查看用户 key

```http
POST /api/external/miniapp/user-keys
Content-Type: application/json
X-CM-App-Id: miniapp_main
X-CM-Timestamp: 1783160000000
X-CM-Nonce: 8f1d1f32-6c7d-4b7e-b9e4-003
X-CM-Signature: <hex>
```

```json
{
  "openid": "miniapp-openid-001",
  "reset": false
}
```

首次生成响应会返回完整 key：

```json
{
  "userKey": {
    "openid": "miniapp-openid-001",
    "key": "cm_user_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "keyPreview": "cm_user_xxxx...xxxx",
    "openVikingUserId": "wx_8db1ee8f655145d6cfa4e286cda3fda3",
    "instanceId": "mr67mzy8-30acf3",
    "created": true
  }
}
```

非首次且 `reset=false` 时只返回 `keyPreview`，不再回显完整 key。`reset=true` 会生成新 key，并替换旧 key。

### API 聊天 SSE

```http
POST /api/external/openclaw/chat/stream
Authorization: Bearer cm_user_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Content-Type: application/json
```

```json
{
  "conversationId": "miniapp-conv-001",
  "message": "请记住我的小程序口令是银杏罗盘。",
  "metadata": {
    "source": "miniapp"
  }
}
```

请求字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `message` | 是 | 用户消息，不能为空 |
| `conversationId` | 否 | 小程序侧会话 ID；为空时使用 `default` |
| `metadata` | 否 | 透传给 API Channel 的附加信息，建议包含 `source=miniapp` |
| `openid` | 否 | 一般不传；如果传入，必须与 key 绑定的 openid 一致 |

SSE 事件：

```text
event:start
data:{"requestId":"...","instanceId":"...","conversationId":"...","openVikingUserId":"wx_..."}

event:delta
data:{"text":"银"}

event:done
data:{"requestId":"...","messageId":"...","openVikingUserId":"wx_...","finishedAt":"..."}
```

错误时返回 `event:error`，并结束 SSE。常见错误包括用户 key 无效、用户未完成扫码绑定、绑定实例不可用、API Channel 未安装或未启动。

## 时序图

### 首次绑定

```mermaid
sequenceDiagram
  participant M as 小程序后端
  participant C as Claw Manager API
  participant D as MySQL
  participant G as OpenClaw Gateway
  participant W as 用户微信

  M->>C: POST /miniapp/wechat-bind-links(openid,HMAC)
  C->>D: 创建 miniapp_user_bindings(pending)
  C->>C: 选择负载最小 ready 实例
  C->>G: 创建微信绑定二维码
  C->>D: 写 wechat_bind_links.miniapp_openid_hash
  C-->>M: 返回 bindToken 和 qrLink
  W->>G: 扫码
  G->>C: 上报扫码结果
  C->>D: 写 wechat_user_id 和 wx_<hash>
  M->>C: GET /miniapp/wechat-bind-links/{token}
  C-->>M: status=connected, canCreateUserKey=true
```

### API 聊天共享微信记忆

```mermaid
sequenceDiagram
  participant M as 小程序后端
  participant C as Claw Manager API
  participant D as MySQL
  participant A as API Channel
  participant O as OpenViking 插件
  participant V as OpenViking Server

  M->>C: POST /openclaw/chat/stream Bearer cm_user_...
  C->>D: 查 miniapp_user_keys 和 miniapp_user_bindings
  C->>A: openVikingUserId=wx_<hash>, senderHash, conversationHash
  A->>A: 写 sender-handoff.json
  A-->>C: SSE delta/done
  O->>A: afterTurn 获取 sessionKey
  O->>O: 从 handoff 解析 wx_<hash>
  O->>V: 用 wx_<hash> 的 user key 写入/召回
```

## 数据库字典

### `miniapp_clients`

小程序后端调用方表，用于 HMAC 鉴权。

| 字段 | 作用 |
| --- | --- |
| `app_id` | 调用方 ID，对应请求头 `X-CM-App-Id` |
| `app_secret` | HMAC secret，当前按明文保存 |
| `enabled` | 是否启用，禁用后请求返回 401 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### `miniapp_request_nonces`

HMAC 防重放表。

| 字段 | 作用 |
| --- | --- |
| `app_id` | 调用方 ID |
| `nonce` | 请求 nonce，和 `app_id` 组成主键 |
| `created_at` | nonce 写入时间 |
| `expires_at` | nonce 过期时间，当前 5 分钟 |

### `miniapp_user_bindings`

小程序用户绑定事实源，保存 openid、OpenClaw 实例、微信用户和 OpenViking 用户的关系。

| 字段 | 作用 |
| --- | --- |
| `openid_hash` | `HMAC(identity_salt, openid).slice(0, 32)`，主键 |
| `openid` | 小程序传入的原始 openid |
| `instance_id` | 首次出码选择的 OpenClaw 实例，二次出码继续复用 |
| `wechat_user_id` | 扫码完成后的微信用户 ID |
| `openviking_user_id` | 微信用户对应的 `wx_<hash>`，API 和微信共享记忆的关键字段 |
| `bind_status` | 绑定状态，例如 `pending`、`waiting_scan`、`connected`、`rejected` |
| `current_bind_token` | 当前二维码 token，对应 `wechat_bind_links.token` |
| `bound_at` | 成功绑定时间 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### `miniapp_user_keys`

小程序用户聊天 key 表。

| 字段 | 作用 |
| --- | --- |
| `openid_hash` | 关联 `miniapp_user_bindings.openid_hash` |
| `openid` | 原始 openid，便于排查和校验 |
| `user_key` | `cm_user_...` 完整用户 key，当前按明文保存 |
| `key_preview` | 脱敏展示值 |
| `enabled` | key 是否启用 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |
| `last_used_at` | 最近生成、查看或聊天使用时间 |

### `wechat_bind_links`

微信二维码出码和扫码状态表。小程序出码复用这张表。

| 字段 | 作用 |
| --- | --- |
| `token` | 绑定链接 token |
| `mode` | 出码模式 |
| `phone` | 绑定流程使用的手机号字段；小程序出码使用合成值 |
| `instance_id` | 出码所在 OpenClaw 实例 |
| `target_account_id` | 二次扫码时指定原微信账号，确保回到同一实例和账号链路 |
| `scanned_wechat_user_id` | 实际扫码得到的微信用户 ID |
| `status` | 二维码状态，例如 `created`、`waiting_scan`、`connected`、`rejected` |
| `qr_mode` | 二维码模式 |
| `qr_payload` | 二维码 payload |
| `qr_link` | 可直接打开的二维码链接 |
| `qr_expires_at` | 二维码过期时间 |
| `error_message` | 出码或扫码错误信息 |
| `created_by_admin_id` | 管理员出码来源；小程序出码可为空 |
| `miniapp_openid_hash` | 小程序 openid hash，用于把扫码结果回写到 `miniapp_user_bindings` |
| `created_at` | 创建时间 |
| `started_at` | 出码开始时间 |
| `expires_at` | 绑定链接过期时间 |
| `completed_at` | 完成时间 |
| `updated_at` | 更新时间 |

### `openviking_user_keys`

OpenViking user key broker 缓存表。

| 字段 | 作用 |
| --- | --- |
| `account_id` | OpenViking account，默认 `claw-manager` |
| `openviking_user_id` | `wx_<hash>` 或旧纯 API 用户的 `api_<hash>` |
| `user_key` | OpenViking Server 返回的用户级 API key |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### 相关表

| 表 | 作用 |
| --- | --- |
| `instances` | OpenClaw 实例元数据，`id/status/container_name/port` 用于路由和可用性判断 |
| `instance_provisioning` | 实例 ready 状态，小程序首绑和聊天都要求目标实例 ready |
| `wechat_paired_accounts` | 已绑定微信账号，二次出码会根据 `wechat_user_id` 查回原账号 |
| `external_api_user_routes` | 旧纯 API openid 路由表；小程序用户聊天不应新建这里的 `api_<hash>` 记忆身份 |

## 二次扫码

同一 openid 再次请求二维码时：

- 读取已有 `miniapp_user_bindings`。
- 使用原 `instance_id` 出码。
- 如果已有 `wechat_user_id`，通过 `wechat_paired_accounts` 找到原微信账号并设置 `target_account_id`。
- 同一微信用户重新扫码后，`openviking_user_id` 保持原 `wx_<hash>`。
- 如果扫码结果被服务端判定为不允许的账号，绑定状态会进入 `rejected`，调用方应提示用户重新处理绑定。

## 验收与排障

双向验收：

1. 小程序出码，用户微信扫码。
2. 查询状态直到 `status=connected` 且 `openVikingUserId=wx_...`。
3. 生成 `cm_user_...`。
4. 微信发送“请记住我的微信代号是白桦灯塔”。
5. API 使用 `cm_user_...` 问“我的微信代号是什么”，应答包含“白桦灯塔”。
6. API 发送“请记住我的小程序口令是银杏罗盘”。
7. 微信问“我的小程序口令是什么”，应答包含“银杏罗盘”。
8. 检查 runner 日志中 API 和微信 afterTurn 均显示同一个 `wx_<hash>`。

常见问题：

| 现象 | 排查点 |
| --- | --- |
| HMAC 接口返回 401 | 检查 `app_id`、`app_secret`、timestamp、nonce、raw body 和签名串 |
| 生成 user key 返回 409 | 用户尚未扫码到 `connected`，或 `openviking_user_id` 为空 |
| 聊天接口返回 401 | `Authorization` 不是有效的 `Bearer cm_user_...` |
| 聊天接口返回 409 | 绑定实例不可用、未 ready 或 API Channel 未安装 |
| 记忆写到错误用户 | 检查 API Channel 日志里的 `openVikingUserId` 和 `sender-handoff.json` |
| OpenViking 没有抽取 task | 检查 OpenViking 插件版本、afterTurn memory intent commit 日志和 `task_id` |
| SSE chunk 重复 | 当前属于 API Channel 流式输出问题，需结合 requestId 查看 runner 日志 |
