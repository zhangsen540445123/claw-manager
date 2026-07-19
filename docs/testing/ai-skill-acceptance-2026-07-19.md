# AI Skill 全量验收测试记录（2026-07-19）

## 测试约束

- 环境：测试环境 Claw Manager、Time Manager、小程序和 OpenClaw Runner。
- 期间只测试和记录，不修改代码、不重启服务、不重新部署。
- 微信侧只允许与 `(*∩_∩*)丧彪(*∩_∩*)` 这个机器人交互，不主动联系其他微信用户。
- 测试数据使用 `AI验收-20260719` 前缀并保留。

## 环境基线

- Claw Manager API/Web、MySQL、Redis 均在线。
- 三个目标 Runner 均在线，插件版本已升级并完成 Gateway 重启。
- CI commit：`5d04fa5`。

## 已完成场景

### API 文本 SSE

- Trace：`cmtrace_c636843945534ae9a17b759aaf4218d2`
- 结果：HTTP 200；`start -> delta x8 -> heartbeat -> done`。
- 结论：通过。未发现乱码、重复、504 或 524。

### 工作区文件工具

- 失败 Trace：`cmtrace_107013bcb4904db4848d95a0a0b49650`
- 失败原因：旧 Gateway 未真正加载升级后的插件，空 `expectedSha256` 被错误拒绝并触发首包超时。
- 重启后成功 Trace：`cmtrace_610e2364fda74ee3aef49860f56d1ebb`
- 结果：成功写入 `notes/AI验收-20260719/hello.txt`。
- 边界：绝对路径、`..` 越界、外部符号链接、删除 workspace 根目录均拒绝。

### 真实生图与 Artifact

- Trace：`cmtrace_24030a48dcf84c99a0bf637b6c5ce3cd`
- 结果：生图、落盘、Artifact 上传、HTML 创建和 API 输出全部成功。
- SSE：`start -> heartbeat x3 -> artifact -> delta -> done`。
- 图片：HTTP 200，`image/png`。
- Artifact：`contentKey=f02df809e29b48209a374978c88c34cc`，`imageId=81549f044c4b47d2b51146c68ae28a6d`。

## 当前发现的问题

### 历史发现：HTML Viewer 曾要求 Open API 认证

- 地址：`https://www.caoxf.nyc.mn/api/open-api/html-content/f02df809e29b48209a374978c88c34cc/view`
- 早期实测：HTTP 401，JSON 错误“缺少 Open API 认证信息”。
- 当前复测：HTTP 200，`Content-Type: text/html;charset=UTF-8`，小程序 Viewer 页面可以正常加载。
- 结论：该问题已在当前部署中恢复，保留历史证据用于回归。

### P1：Trace ID 精确查询未按 ID 过滤

- 输入：`cmtrace_24030a48dcf84c99a0bf637b6c5ce3cd`。
- 预期：只返回该 Trace。
- 实际：返回其他 6 条历史链路，且不包含输入的 Trace。
- 影响：无法可靠通过 Trace ID 定位单条链路，后台排障入口不可信。

### P1：OpenViking API Key 无效

- 三个 Runner 日志持续出现 `OpenViking request failed [UNAUTHENTICATED]: Invalid API Key`。
- 影响：记忆召回和 afterTurn 记忆沉淀失败，依赖历史记忆的 Skill 结果不可信。

### P1：动态用户 Agent 未落地

- 三个实例的 `agents.list` 和 `bindings` 仍为空。
- API 请求仍使用 `sessionKey=agent:main:...`。
- 未观察到 `wechat dynamic agent bound`。
- 影响：尚未证明用户 workspace 和上下文隔离。

### P1：指定微信机器人尚未完成真实入站验收

- 最近 6 小时三个目标 Runner 未观察到新的 `wechat.inbound.received`、`wechat.media.send.completed` 或 `wechat dynamic agent bound` 事件。
- 当前只能确认 API 渠道链路，不能据此证明指定微信机器人链路正常。
- 待获得发送确认后，仅向 `(*∩_∩*)丧彪(*∩_∩*)` 发送测试消息并补齐 Trace。

### P1：实例配置重写未生效

- `openclaw.json` 仅看到 `plugins.slots.contextEngine=openviking`。
- 未看到 `skipBootstrap`、`compaction.memoryFlush`、`plugins.slots.memory`、`session.dmScope`。
- 共享 workspace 仍有 `/workspace/MEMORY.md` 和 `memory/*.md`。
- 影响：旧本地记忆生成/共享风险仍未消除。

### P2：旧 Gateway 曾触发 300 秒首包超时

- Trace：`cmtrace_107013bcb4904db4848d95a0a0b49650`。
- 重启并加载新插件后同类工作区请求成功；保留该记录，需后续确认所有实例均实际加载新版本。

### P2：小程序开发者工具存在非本次链路的前端错误

- 控制台当前显示 `/auth/verify`、`/marquee/year-goal` 请求超时。
- 静态资源 `/images/添加_bg.png` 多次返回 HTTP 500。
- 影响：登录状态/首页辅助信息和部分装饰资源异常；未阻断已验证的 HTML Viewer，但应单独修复。

### P1：OpenViking 认证失败持续存在

- 三个 Runner 均出现 `OpenViking request failed [UNAUTHENTICATED]: Invalid API Key`。
- 影响：自动召回、上下文组装和 afterTurn 沉淀不可作为 Skill 验收依据。

### P2：后台 API Channel 插件初始检测状态陈旧

- Runner 文件系统确认三个实例均加载 `@claw-manager/openclaw-api-channel@2026.6.50`。
- 首次打开“插件管理 -> API Channel 插件”时显示三个实例均为“未检测”，当前版本和最新版本均为 `-`。
- 同一页面的微信 `2026.6.28`、OpenViking `2026.6.37`、Bridge `2026.7.20` 和工作区文件 `2026.7.2` 能正确显示。
- 对一个实例执行“检测”后，三个实例均恢复显示 `2026.6.50`、已安装、不可升级。
- 影响：首次进入页面会误导管理员，需要手动检测或刷新后才能看到真实状态。

## 待完成测试

- 后台链路追踪详情和诊断字段。
- HTML Viewer 在小程序弹窗入口和历史入口的实际跳转。
- 指定微信机器人入站、动态 Agent 和生图/Artifact 链路。
- 15 个 Skill 的剩余业务流程和异常场景。
- 最终按 P0-P3 汇总问题、Trace、Artifact 和新增数据。
