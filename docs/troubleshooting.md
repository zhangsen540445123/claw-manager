# 常见问题与排障

## Gateway 首次启动很久

首次启动 OpenClaw Gateway 可能需要较长时间。当前默认 ready 等待窗口是 30 分钟：

```text
OPENCLAW_GATEWAY_READY_TIMEOUT_MS=1800000
```

排查顺序：

1. 看后台实例 provisioning 状态。
2. 看 API 日志。
3. 看目标 Runner 容器日志。
4. 确认 Runner 镜像可拉取，Docker socket 已挂载。

## Control UI 打不开

优先检查：

- API 容器是否正常运行。
- 目标 Gateway 是否 ready。
- `OPENCLAW_CONTROL_UI_ALLOWED_ORIGINS` 是否包含当前 Web 访问来源。
- 浏览器访问的是外层 Web 代理地址，不是 Runner 容器内地址。

## 微信插件安装或升级失败

同一个实例中，微信插件、API Channel 插件和 OpenViking 插件安装类操作需要串行执行。遇到失败时：

1. 确认另一个插件没有正在安装、升级、重装或卸载。
2. 查看插件管理页任务状态。
3. 查看 Runner 日志中的 `openclaw plugins` 输出。
4. 重新检测版本后再执行重新安装。

## OpenViking 没有召回记忆

排查顺序：

1. OpenViking预设是否配置了 base URL、身份盐值和 Root API Key。
2. 目标实例是否已安装 OpenViking 插件。
3. 微信或 API Channel 日志中是否出现 OpenViking handoff。
4. API 日志是否出现 broker resolve。
5. `openviking_user_keys` 是否存在对应 `account_id` 和 `openviking_user_id`。
6. Runner 日志是否出现 `identity_missing.skip_*`。

如果日志出现：

```text
openviking: skipping auto-recall because precheck failed (health check failed)
```

通常表示 OpenViking `/health` 快速预检查短暂失败或超时。若随后出现 `identity profile recall injected profile.md`，说明后续召回路径已经成功注入身份记忆。

## 清理 OpenViking 用户后仍使用旧 key

如果直接在 OpenViking Server 删除某个用户，Claw Manager 本地 `openviking_user_keys` 可能仍缓存旧 user key。需要同时清理本地缓存，让下一次微信消息重新注册用户并获取新 key。

OpenViking Admin 删除用户示例：

```bash
curl -X DELETE "http://127.0.0.1:1933/api/v1/admin/accounts/claw-manager/users/wx_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "X-API-Key: <OPENVIKING_ROOT_API_KEY>"
```

注意这里必须使用 Root API Key，不是 user key。

## 小程序 HMAC 接口返回 401

小程序出码和生成用户 key 使用 HMAC 鉴权。排查顺序：

1. `miniapp_clients` 中是否存在对应 `app_id`，且 `enabled=1`。
2. 请求头是否包含 `X-CM-App-Id`、`X-CM-Timestamp`、`X-CM-Nonce`、`X-CM-Signature`。
3. `X-CM-Timestamp` 是否为毫秒时间戳，且与服务器时间偏差不超过 5 分钟。
4. 同一个 `app_id + nonce` 是否重复使用；重复会被 `miniapp_request_nonces` 拒绝。
5. 签名串是否严格为 `METHOD\nPATH_WITH_QUERY\nX-CM-TIMESTAMP\nX-CM-NONCE\nSHA256(rawBody)`。
6. `rawBody` 是否与实际发送内容完全一致，包含空格和字段顺序。

## 小程序生成用户 key 返回 409

`POST /api/external/miniapp/user-keys` 只有在微信扫码绑定完成后才允许生成 `cm_user_...`。排查顺序：

1. `miniapp_user_bindings.bind_status` 是否为 `connected`。
2. `miniapp_user_bindings.openviking_user_id` 是否为 `wx_...`。
3. `miniapp_user_bindings.instance_id` 对应实例是否仍存在并 ready。
4. 同一 `openid` 二次出码是否仍落在原 `instance_id`，没有被错误分配到新实例。

## API Channel 未检测到 heartbeat

后台提示：

```text
OpenClaw API Channel 已请求启动，但未检测到队列 monitor heartbeat
```

排查顺序：

1. 目标 Runner 是否安装 `@claw-manager/openclaw-api-channel`。
2. 重启 Gateway 后，Runner 启动日志中是否出现 `claw-manager-api` channel 注册和 monitor 启动日志。
3. 插件管理页检测到的插件版本是否为预期版本。
4. Runner 挂载目录下 `.openclaw/claw-manager-api` 是否能创建 `requests`、`streams`、`responses` 等目录。
5. 若日志出现 `channels.start invalid channel`，优先确认插件是否已在 Gateway 启动时完成注册。

## 小程序 API 记忆写到错误用户

小程序聊天应使用 `cm_user_...` 解析到 `miniapp_user_bindings.openviking_user_id=wx_<hash>`，不应新建 `api_<hash>`。排查顺序：

1. 聊天请求是否使用 `Authorization: Bearer cm_user_...`，而不是旧外部聊天共享凭据。
2. 请求 body 如果带了 `openid`，是否与 key 绑定的 openid 一致。
3. `miniapp_user_bindings.openviking_user_id` 是否和微信绑定对应的 `wx_<hash>` 一致。
4. API 日志和 Runner 日志中的 `openVikingUserId` 是否为同一个 `wx_<hash>`。
5. API Channel handoff 文件中当前 `sessionKey` 对应的 `openVikingUserId` 是否正确。
6. `external_api_user_routes` 不应出现本次小程序用户的新路由；这张表只用于旧纯 API openid 路由。

## OpenViking 没有生成 task 或记忆文件

消息已经到达 OpenClaw，但 OpenViking 侧没有产生 task 或记忆文件时：

1. Runner 日志是否出现 OpenViking afterTurn 捕获消息日志。
2. 对明确记忆意图，例如“请记住”“我叫”“我喜欢”，日志是否出现 memory intent commit。
3. OpenViking Server 的 LLM 和 embedding 能力是否正常。
4. `openviking_user_keys` 中该 `wx_<hash>` 的 user key 是否存在且未过期。
5. OpenViking session 的 `messages.jsonl` 是否写入到对应 `wx_<hash>` 用户目录。

## SSE chunk 重复或首字响应慢

API 聊天接口应通过 `event:delta` 增量返回。排查顺序：

1. Runner 日志中该 requestId 是否有 `agentEventDeltaCount > 1`。
2. 若只有最终整段输出，检查 API Channel 的 agent-event bridge 是否注册成功。
3. 若固定短串出现重复，检查是否同时发送了 agent-event delta 和最终 deliver 兜底块。
4. 检查 Web/Nginx 是否对 `/api/external/openclaw/chat/stream` 关闭了 proxy buffering。
5. 客户端解析 SSE 时应按 `event` 分帧拼接 `delta.text`，不要把 `done` 或重试响应重复拼入正文。
