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

同一个实例中，微信插件和 OpenViking 插件安装类操作需要串行执行。遇到失败时：

1. 确认另一个插件没有正在安装、升级、重装或卸载。
2. 查看插件管理页任务状态。
3. 查看 Runner 日志中的 `openclaw plugins` 输出。
4. 重新检测版本后再执行重新安装。

## OpenViking 没有召回记忆

排查顺序：

1. OpenViking预设是否配置了 base URL、身份盐值和 Root API Key。
2. 目标实例是否已安装 OpenViking 插件。
3. 微信消息日志中是否出现 sender handoff。
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
