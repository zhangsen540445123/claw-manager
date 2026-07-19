# 统一用户 Agent 端到端验收记录

测试日期：2026-07-19  
测试环境：Claw Manager 测试环境（全新数据库，5 个 OpenClaw 实例）  
测试用户：微信发送方仅使用 `(*∩_∩*)丧彪(*∩_∩*)`；小程序使用微信开发者工具当前登录用户。

## 执行约束

- 测试期间只记录问题，不修改代码、不重启服务、不重新部署。
- 不记录完整 openid、微信用户 ID、用户 Key、API Key 或 Authorization。
- 测试数据和 OpenViking 记忆保留至验收结束。

## 扫码前基线

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| Claw Manager API/MySQL/Redis | 通过 | 三个服务均为 `healthy` |
| OpenClaw 实例数量 | 通过 | 共 5 个，均为 `running`、provisioning `ready/100%` |
| Gateway 健康 | 通过 | 19001-19005 的 `/health` 均返回 HTTP 200 |
| API Channel | 通过 | 5 个实例均为 `2026.6.51` |
| 微信插件 | 通过 | 5 个实例均为 `2026.6.29` |
| OpenViking 插件 | 通过 | 5 个实例均为 `2026.6.37` |
| Workspace File 插件 | 通过 | 5 个实例均为 `2026.7.2` |
| Miniapp Bridge | 通过 | 5 个实例均为 `2026.7.20` |
| 用户身份相关表 | 通过 | identity、miniapp binding/key、OpenViking user key、微信配对/渠道记录均为 0 |
| 动态 Agent/binding | 通过 | 5 个实例均只有隐式 `main`，动态 Agent 与 binding 为 0 |
| 动态 workspace | 通过 | 5 个实例的 `workspace-user_*` 数量均为 0 |

### 配置复核

- Runner 的生效配置为 `/var/lib/openclaw/openclaw.json`。已确认包含：
  - `plugins.slots.contextEngine=openviking`
  - `plugins.slots.memory=none`
  - `session.dmScope=per-account-channel-peer`
  - `agents.defaults.skipBootstrap=true`
  - `agents.defaults.compaction.memoryFlush.enabled=false`
- `.openclaw/openclaw.json` 是另一份非生效文件，不能用于判断当前 Gateway 配置。

## 场景记录

### UA-E2E-01 扫码即创建统一身份与 Agent

结果：**失败（P1）**

- 小程序扫码链接在实例 `mrrdhj8g-c8c3ed` 完成绑定，状态为 `connected`。
- 扫码后生成且仅生成 1 条 `user_agent_identities` 和 1 条 `miniapp_user_bindings`。
- binding 与 identity 的 `agentId`、`openVikingUserId`、`wechatUserId` 三项完全匹配。
- 随机 Agent 预览：`user_4b64c0d...0130`。
- OpenViking 用户预览：`wx_8db1e...fda3`。
- 目标实例创建了 `workspace-user_*`、Agent 目录和六个预设文件。
- 其余四个实例没有新增用户 workspace。
- **异常**：生效的 `/var/lib/openclaw/openclaw.json` 中动态 Agent 数量仍为 0、binding 数量仍为 0。
- Runner 日志先后两次记录 `user agent ensured`，但配置文件修改时间未更新。
- 初步定位：API Channel 收到 `ensure_user_agent` 后创建 workspace；当 `configRuntime.mutateConfigFile` 不可用时仅构造内存 draft，队列处理方没有持久化或应用返回的配置，却仍记录成功。

后续继续验证小程序首次聊天的临时路由，以及微信渠道是否因缺少持久 binding 拒绝回退到 `agent:main`。

### UA-E2E-02 小程序首次聊天路由、workspace 写入与 SSE

结果：**部分通过（P1）**

- 小程序生成用户 Key 后，`miniapp_user_keys` 与 `openviking_user_keys` 各新增且仅新增 1 条，均与统一身份匹配。
- Trace：`cmtrace_a192801a4a9d430e9a407f8cb69416ce`。
- API requestId：`a192801a-...-16ce`。
- API Channel 使用统一 Agent 预览 `user_4b64c0d...0130` 和统一 OpenViking 身份。
- API 与微信使用不同 session key；API session 仍归属于同一 Agent。
- 小程序 Network 中 `/api/ai/chat/stream` 返回 HTTP 200，总耗时约 1.1 分钟；页面先显示“对方正在输入…”，完成后提示消失。
- Trace 出现 `api.request.received -> api.dispatch.started -> api.dispatch.completed -> api.stream.completed`。
- `workspace_file` 成功写入 `notes/e2e/shared.txt`，服务端核验内容为 `OWNER=A;TOKEN=WS-A-K7M4`。
- OpenViking afterTurn 执行强制 commit，状态为 `accepted` 且生成 task ID。
- **异常**：模型只回复 workspace 写入成功，没有确认 `memory_store` 成功；Trace 也未记录 workspace/OpenViking 工具生命周期。
- **异常**：小程序聊天后，生效配置中动态 Agent 和 binding 仍为 0；API Channel 使用临时内存配置完成路由，没有持久化 API binding。

### UA-E2E-03 微信首次消息与跨渠道 workspace 读取

结果：**部分通过（P1）**

- 微信 Trace：`cmtrace_8325bf5da81948ef9cc4db2feadd3dc3`。
- 微信插件在首条消息中成功持久化同一个随机 Agent 和微信 direct binding，并触发 OpenClaw 热重载。
- 生效配置最终包含 1 个动态 Agent、1 个微信 binding，Agent 工具 deny 为 `write/edit/apply_patch/exec/process`。
- 微信成功读取小程序写入的同一 workspace 文件，证明两个渠道最终共享 Agent/workspace。
- 动态 Agent 下存在 API、微信和 main 三个 session；API 与微信 session key 不同。
- 其余四个实例仍保持 0 Agent、0 binding、0 用户 workspace。
- **异常**：微信回复只识别出文件中的测试 Token，未召回城市和饮品。
- OpenViking 日志显示 auto-recall 因“无最近健康检查”跳过；手工执行插件 status 后远端服务健康为 `ok=true`、版本兼容。

### UA-E2E-04 日志脱敏

结果：**失败（P1）**

- Runner 的 API Channel 与 OpenViking 日志仍输出完整 `openVikingUserId`，不符合“不记录完整用户身份”的验收要求。
- Runner 日志还会输出完整 session key、记忆摘要和部分会话内容，超出约定的脱敏技术元数据范围。
- 测试报告仅保留身份预览，不复制完整日志值。

### UA-E2E-05 跨渠道 workspace 双向访问与边界隔离

结果：**通过**

- 小程序通过 `workspace_file` 写入 `notes/e2e/shared.txt`，内容为 `OWNER=A;TOKEN=WS-A-K7M4`。
- 微信随后读取到同一文件的完整内容。
- 微信通过 `workspace_file` 写入 `notes/e2e/from-wechat.txt`，内容为 `CHANNEL=WECHAT;TOKEN=WS-B-M3P9`。
- 小程序随后读取到同一文件的完整内容。
- 微信尝试读取 `../openclaw.json`，被拒绝并返回 `workspace file path escapes workspace`。
- 小程序尝试读取绝对路径 `/etc/passwd`，被拒绝并返回 `workspace file path must be relative`。
- 两个渠道 session key 不同，但最终均归属于同一个动态 Agent 和同一个 workspace。

### UA-E2E-06 OpenViking 统一身份、写入与跨渠道召回

结果：**失败（P1）**

- API Channel 和微信插件日志均显示使用同一个持久化 OpenViking 用户预览 `wx_8db1e...fda3`，Claw Manager 身份表、binding 和用户 Key 也保持一一对应。
- 小程序和微信显式调用 `memory_recall`、`memory_store` 均失败，错误为 `OpenViking user identity is unavailable for this turn.`。
- 小程序首次会话的 afterTurn 自动提交成功，OpenViking 远端可检索到 `海盐-K7M4`、`青岛`、`桂花乌龙`。
- 显式 `memory_store` 报错的 `月桂-P8Q2` 后续仍被 afterTurn 自动捕获，形成“工具报告失败但后台实际写入”的不一致行为。
- 微信自然语言复核 Trace：`cmtrace_f363f1ab90404d95bb5a90aab926074e`。Runner 找到 24 个候选记忆，但最高分约 `0.011`，低于 `0.15` 阈值，最终未注入并回复无法读取记忆。
- 小程序自然语言复核 Trace：`cmtrace_b818c4782bf94b958d4eb70dedbb9d62`。请求 HTTP 200，约 46 秒完成；OpenViking 注入了 1 条同用户记忆，但召回的是不相关的“小程序代号”记忆，未回答微信侧项目代号和周末运动。
- 结论：两个渠道已经关联到同一个远端 OpenViking 用户，但显式工具身份传播损坏，自动召回相关性也不足，不能判定“跨渠道记忆可用”。

### UA-E2E-07 环境肃清与远端历史记忆

结果：**失败（P1）**

- Claw Manager 数据库、动态 Agent 和本地 workspace 清空后，重新扫码生成的 OpenViking 用户 ID 仍与之前一致。
- 直接查询 OpenViking 时发现该身份下存在历史记忆。
- 原因是 OpenViking 用户 ID 由扫描身份和未变化的 salt 确定性派生；只清空 Claw Manager 不会删除远端用户记忆。
- 真正的全新环境验收需要在扫码前删除对应 OpenViking 远端用户数据，或轮换 identity salt 后再创建身份。

### UA-E2E-08 SSE、并发与输入体验

结果：**部分通过（P2）**

- 所有小程序 AI 请求均返回 HTTP 200，未出现 504 或 524。
- 页面能显示“对方正在输入…”，正文到达后提示消失；长等待期间 heartbeat 保持连接。
- 首次请求 12 个 delta，首段正文约 60.5 秒，总耗时约 61.9 秒。
- 反向 workspace 请求 74 个 delta，首段正文约 28.1 秒，总耗时约 30.1 秒。
- 并发 API Trace：`cmtrace_fd041a6125e747ca9cb87b909af0ab41`；并发微信 Trace：`cmtrace_d379a7e1d9af4d5c97768938bca63442`。两条调度实际重叠约 9 秒，未串 Agent、workspace 或 session。
- 并发微信消息在 API 入站约 22 秒后才送达，不满足原计划“2 秒内同时发送”的严格输入条件，但仍验证了执行重叠时的隔离性。
- 小程序长消息会静默截断，未显示最大长度或截断提示；一次测试指令被截断在 `memory_sto`，需拆分为短消息继续执行。

### UA-E2E-09 最终 Agent、binding 与实例隔离复核

结果：**部分通过（P1）**

- 数据库最终计数：identity 1、miniapp binding 1、OpenViking user key 1、miniapp user key 1。
- 目标实例最终只有 1 个随机 `user_*` Agent、1 个微信 direct binding 和 1 个 `workspace-user_*`。
- 未生成 `api-*` Agent，未创建第二个用户 workspace，也未路由到独立的 API Agent。
- 其余四个 OpenClaw 实例的动态用户 workspace 数量均为 0。
- `openclaw agents list --bindings --json` 显示动态 Agent 工具 deny 包含原生写入和 Shell 工具，微信 binding 指向该 Agent。
- **异常**：API binding 仍未持久化；小程序请求依赖 API Channel 每次传入的临时显式配置完成路由。

### UA-E2E-10 官方配置校验

结果：**通过配置语法校验，存在独立安全告警**

- `openclaw config validate --json` 返回 `valid=true`，生效路径为 `/var/lib/openclaw/openclaw.json`。
- `openclaw doctor --lint --json` 没有发现配置语法错误，但整体 `ok=false`。
- 主要告警包括配置中存在明文 Gateway token/模型 API Key、Gateway 绑定 LAN，以及部分 bundled Skill 不可用。
- 上述告警与统一 Agent 路由缺陷分开记录，后续应独立处理安全配置和 Skill 可用性。

## 最终结论

总体结果：**验收不通过**。

已通过：

- 扫码后身份、binding、用户 Key 和 workspace 保持单份。
- 微信与小程序最终共享同一个随机 Agent 和同一个 workspace。
- workspace 双向读写成功，绝对路径和 `..` 越界均被拒绝。
- 五实例之间没有串 Agent 或 workspace。
- SSE 保持连接有效，未出现 504/524，并发执行未串 session。
- OpenClaw 生效配置通过官方语法校验。

阻断验收的 P1：

1. 扫码阶段 `ensure_user_agent` 在 `mutateConfigFile` 不可用时误报成功，Agent/binding 未写入生效配置；需等待微信首条消息补写。
2. API binding 没有持久化，小程序路由依赖临时配置。
3. 两个渠道的 `memory_store`、`memory_recall` 均拿不到当前 turn 的 OpenViking 用户身份。
4. OpenViking 自动召回存在低分全部过滤或召回错误记忆，跨渠道记忆无法稳定使用。
5. 仅肃清 Claw Manager 会重新连接旧 OpenViking 远端记忆，当前测试环境不是严格全新记忆环境。
6. Runner 日志泄露完整 OpenViking 用户 ID、session key、记忆摘要和部分会话内容。

体验问题 P2：

- 小程序长输入静默截断，没有字符限制提示。
- 普通工具请求首段正文仍可能等待 28-60 秒，虽然 heartbeat 已避免网关超时。
