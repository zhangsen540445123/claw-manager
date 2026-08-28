# OpenClaw OOM 根因诊断 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改 Runner 基础镜像的前提下，为五个生产 OpenClaw 实例建立“Node/V8 进程内指标 + 低开销分配采样 + OpenClaw 运行阶段事件 + `/proc`/cgroup 外部指标 + 自动归因分析”的完整证据链，并先移除现有 Heap Snapshot 诊断器造成的直接 OOM 风险，使 2026 年 8 月 29 日能够确定内存增长属于 JS Heap、Buffer/ArrayBuffer、native/allocator、资源句柄或混合类型，定位到 Heartbeat、微信、API、模型、上下文或工具执行阶段，并在 JS Heap/Buffer 包装对象可见时输出主要分配调用栈。

**Architecture:** API Channel 插件作为 OpenClaw PID 1 内部探针，通过正式的插件后台服务和 `internalDiagnostics.onEvent` 采集 Node/V8/GC/事件循环/活动资源及 Agent Runtime 阶段事件；同时使用不开放网络端口的本地 `inspector.Session` 周期生成 Sampling Heap Profile，并在安全阈值生成删除敏感字段后的 Node diagnostic report。Java 后端继续从容器外采集 cgroup、`/proc`、Docker 生命周期和匿名映射分类。两层数据通过 `instanceId + processStartId + timestamp + runIdHash` 对齐，独立的 Node 分析脚本自动完成分段拟合、阶段 retained delta、主要分配栈和根因分类。Heap Snapshot 与普通采集彻底拆分，五个 2 GiB 实例默认禁用快照，只允许一个提升到 4 GiB 的受控实例在具有至少 2 GiB headroom 时生成一次有效快照。

**Tech Stack:** Spring Boot 3 / Java 21 / docker-java / TypeScript / Node.js 22 / V8 API / `node:perf_hooks` / Vitest / JUnit 5 / Node built-in test runner。

**Spec:** `docs/oom-diagnostics.md`（现有诊断事实和部署说明；Task 8 将按本计划更新为新的事实源）。

## Global Constraints

- 不修改 Runner 基础镜像、Dockerfile 或 `containers/openclaw-runner/entrypoint.sh`。
- 不开放 Node Inspector 网络端口，不使用 `--inspect`/`--inspect-brk`；允许插件内部使用不监听端口的 `inspector.Session` 执行 Sampling Heap Profiler。不强制执行 `global.gc()`，不在业务线程中生成 Heap Snapshot。
- 不采集消息正文、提示词、工具输出、文件内容、URL、token、Cookie、微信身份、原始 sessionKey/sessionId。
- OpenViking 服务端用户和记忆不得删除或修改。
- 普通采集失败不得影响微信、API、Heartbeat、Agent Runtime 或 Gateway 生命周期。
- 五个实例普通采集全部开启；Heap Snapshot 默认关闭，且不得再次在 2 GiB、82% 使用率时触发。
- 诊断文件目录权限为 `0700`，普通 JSON/JSONL 文件权限为 `0600`；Heap Snapshot 按高度敏感文件处理。
- 所有周期任务、监听器和写队列必须支持插件重载幂等，不能由诊断代码自身形成 timer/listener/Promise 泄漏。
- 诊断结论必须区分“长期内存保留根因”和“导致某次进程死亡的直接触发器”。当前已确认的直接触发器是 82% 阈值自动 Heap Snapshot；长期根因仍需本计划采集确定。

---

## 1. 已确认事实与本次必须回答的问题

### 1.1 已确认事实

1. 五个实例均由宿主机 kernel journal 确认为 `Memory cgroup out of memory`，被杀进程是 OpenClaw PID 1，退出码为 137。
2. OOM 前约 94%–95% 是 PID 1 的匿名内存；文件缓存、内核内存、文档 worker 和其他子进程不是主要持有者。
3. `/proc/1/maps` 出现数千个匿名映射，其中大量映射大小恰为 256 KiB。
4. 五个实例重启后 RSS 以约 47–73 MB/hour 线性增长；`mskgk332-da8bdb` 增长最快。
5. 周期性 Agent Runtime/Heartbeat 附近可见 40–100 MiB 阶跃，但没有 Node 内部指标，尚不能判断阶跃来自 JS Heap、external memory 还是 native allocator。
6. 当前自动 Heap Snapshot 在 82% 使用率触发；五次均在发出 `SIGUSR2` 后 29–37 秒发生 OOM，且生成的文件均为 0 字节。
7. OpenViking 首次周期运行出现 `TURN_IDENTITY_CHANNEL_MISMATCH` 后被当前进程 quarantine，后续上下文路径回退 legacy；因此不能把持续增长简单归因于 OpenViking。

### 1.2 次日必须输出的答案

分析程序必须针对每个进程生命周期回答：

- RSS 增长是否主要来自 `heapUsed/old_space`？
- 是否主要来自 `external/arrayBuffers`？
- 是否是 Node 内部指标稳定但匿名映射和 RSS 增长，即 native addon/glibc allocator/碎片？
- `number_of_native_contexts` 或 `number_of_detached_contexts` 是否增长？
- 哪类 active resource（Timeout、TCPWrap、FSReqCallback 等）持续增长？
- GC 是否频繁执行但基线不回落，还是几乎没有 GC 压力？
- retained delta 主要发生在哪种 run：Heartbeat、微信、API 或其他？
- retained delta 主要发生在哪个阶段：上下文组装、模型调用、工具调用、交付或 run cleanup？
- OpenViking quarantine 前后增长斜率是否不同？这一项通过事件时间线与现有 Gateway 错误时间对齐，不从日志采集正文。
- OOM/重启是否发生，以及 cgroup `oom`/`oom_kill` delta 在哪个进程生命周期增加？

---

## 2. 文件结构与职责边界

### 新建文件

- `plugins/openclaw-api-channel-plugin/src/oom-diagnostics.ts`
  - 进程内采样、GC 聚合、事件循环监控、OpenClaw 结构化事件关联、文件写入和生命周期清理。
- `plugins/openclaw-api-channel-plugin/src/oom-diagnostics.test.ts`
  - 插件探针的单元测试和敏感字段防泄漏测试。
- `plugins/openclaw-api-channel-plugin/src/oom-allocation-profiler.ts`
  - 封装本地 `inspector.Session` Sampling Heap Profiler、15 分钟滚动 profile、阈值诊断报告和生命周期释放。
- `plugins/openclaw-api-channel-plugin/src/oom-allocation-profiler.test.ts`
  - 验证采样轮换、文件上限、Inspector 不可用降级、敏感字段删除和 stop/disconnect。
- `backend/src/main/java/com/clawbotforall/runtime/RuntimeDiagnosticsState.java`
  - Docker 容器内存限制、启动时间、重启计数和当前状态的只读快照。
- `backend/src/main/java/com/clawbotforall/diagnostics/ProcMapsSummary.java`
  - `/proc/<pid>/maps` 分类结果值对象和解析逻辑。
- `backend/src/test/java/com/clawbotforall/diagnostics/ProcMapsSummaryTest.java`
  - maps bucket、匿名判断和 256 KiB 计数测试。
- `scripts/analyze-openclaw-oom.mjs`
  - 跨平台、无第三方依赖的次日自动分析器。
- `scripts/tests/analyze-openclaw-oom.test.mjs`
  - 分析器分类、跨重启分段和 retained delta 测试。
- `scripts/tests/fixtures/oom/`
  - JS Heap、ArrayBuffer、native/allocator、资源句柄和跨重启样本。

### 修改文件

- `plugins/openclaw-api-channel-plugin/index.ts`
  - 注册独立的 OOM diagnostics background service；现有 assistant event bridge 保持不变。
- `plugins/openclaw-api-channel-plugin/index.test.ts`
  - 验证服务注册和重复注册幂等。
- `plugins/openclaw-api-channel-plugin/package.json`
  - 发布时递增到 npm 中下一个未占用版本。
- `plugins/openclaw-api-channel-plugin/openclaw.plugin.json`
  - manifest 版本与 package version 同步。
- `backend/src/main/java/com/clawbotforall/config/ClawbotProperties.java`
  - 普通采集、Node 探针和 Heap Snapshot 配置彻底拆分。
- `backend/src/main/resources/application.yml`
  - 新环境变量绑定和安全默认值。
- `backend/src/main/java/com/clawbotforall/runtime/OpenClawRuntime.java`
  - 新增兼容默认实现的 `inspectDiagnosticsState()`。
- `backend/src/main/java/com/clawbotforall/runtime/DockerJavaOpenClawRuntime.java`
  - 注入插件诊断环境变量；读取 Docker memory limit/restart count/startedAt；只有安全快照白名单实例注入 signal 参数。
- `backend/src/main/java/com/clawbotforall/diagnostics/OomDiagnosticsService.java`
  - maps 分类、process identity、cgroup delta、快照安全校验和 0 字节文件处理。
- `backend/src/main/java/com/clawbotforall/diagnostics/OomDiagnosticsScheduler.java`
  - 继续跨实例容错，并记录采样耗时/跳过原因。
- `backend/src/test/java/com/clawbotforall/diagnostics/OomDiagnosticsServiceTest.java`
- `backend/src/test/java/com/clawbotforall/diagnostics/OomDiagnosticsSchedulerTest.java`
- `backend/src/test/java/com/clawbotforall/runtime/DockerJavaOpenClawRuntimeOpenVikingTest.java`
- `compose.yaml`
- `.env.example`
- `docs/oom-diagnostics.md`

---

## 3. 公共接口和数据格式

### 3.1 API Channel 插件配置

Runner 环境中新增/保留：

```text
CLAW_MANAGER_OOM_DIAGNOSTICS_ENABLED=true
CLAW_MANAGER_OOM_DIAGNOSTICS_DIR=/var/lib/openclaw/diagnostics/oom
CLAW_MANAGER_OOM_DIAGNOSTICS_INTERVAL_MS=15000
CLAW_MANAGER_OOM_DIAGNOSTICS_RETENTION_DAYS=3
CLAW_MANAGER_OOM_DIAGNOSTICS_INSTANCE_ID=<instanceId>
CLAW_MANAGER_OOM_AGENT_EVENT_DIAGNOSTICS_ENABLED=true
CLAW_MANAGER_OOM_DIAGNOSTICS_MAX_FILE_MIB=128
CLAW_MANAGER_OOM_DIAGNOSTICS_MAX_TRACKED_RUNS=200
CLAW_MANAGER_OOM_ALLOCATION_SAMPLING_ENABLED=true
CLAW_MANAGER_OOM_ALLOCATION_SAMPLING_INTERVAL_BYTES=524288
CLAW_MANAGER_OOM_ALLOCATION_PROFILE_WINDOW_MS=900000
CLAW_MANAGER_OOM_ALLOCATION_PROFILE_MAX_FILES=16
CLAW_MANAGER_OOM_PROCESS_REPORT_THRESHOLDS=50,65,75
```

关闭时不创建 timer、PerformanceObserver、event listener 或文件。

### 3.2 Node 周期样本

写入：

```text
/var/lib/openclaw/diagnostics/oom/node-metrics-YYYY-MM-DD.jsonl
/var/lib/openclaw/diagnostics/oom/node-latest.json
```

单行结构：

```ts
type NodeMemoryMetric = {
  schemaVersion: 1;
  kind: "node.memory.sample";
  timestamp: string;
  epochMs: number;
  instanceId: string;
  processStartId: string;
  pid: number;
  uptimeMs: number;
  memory: {
    rssBytes: number;
    heapTotalBytes: number;
    heapUsedBytes: number;
    externalBytes: number;
    arrayBuffersBytes: number;
  };
  heap: {
    totalHeapSizeBytes: number;
    totalHeapSizeExecutableBytes: number;
    totalPhysicalSizeBytes: number;
    totalAvailableSizeBytes: number;
    usedHeapSizeBytes: number;
    heapSizeLimitBytes: number;
    mallocedMemoryBytes: number;
    peakMallocedMemoryBytes: number;
    externalMemoryBytes: number;
    nativeContexts: number;
    detachedContexts: number;
  };
  heapSpaces: Record<string, {
    sizeBytes: number;
    usedBytes: number;
    availableBytes: number;
    physicalBytes: number;
  }>;
  activeResources: Record<string, number>;
  eventLoop: {
    delayMinMs: number;
    delayMaxMs: number;
    delayMeanMs: number;
    delayP50Ms: number;
    delayP95Ms: number;
    delayP99Ms: number;
    utilization: number;
  };
  gc: Record<string, { count: number; durationMs: number }>;
  resourceUsage: {
    userCpuMicros: number;
    systemCpuMicros: number;
    maxRssKiB: number;
    minorPageFaults: number;
    majorPageFaults: number;
    voluntaryContextSwitches: number;
    involuntaryContextSwitches: number;
  };
  activeRunCounts: {
    total: number;
    heartbeat: number;
    wechat: number;
    api: number;
    other: number;
  };
};
```

### 3.3 Agent Runtime 事件样本

优先使用 OpenClaw 正式服务上下文：

```ts
ctx.internalDiagnostics?.onEvent((event, metadata) => { ... })
```

不改变现有 `installOpenClawInternalAgentEventBridge()`；该桥继续只负责 API assistant 流。

写入：

```text
/var/lib/openclaw/diagnostics/oom/agent-events-YYYY-MM-DD.jsonl
```

允许记录的事件类型：

```text
run.started
run.completed
harness.run.started
harness.run.completed
harness.run.error
model.call.started
model.call.completed
model.call.error
context.assembled
tool.execution.started
tool.execution.completed
tool.execution.error
tool.execution.blocked
message.queued
message.processed
message.delivery.started
message.delivery.completed
message.delivery.error
diagnostic.memory.sample
diagnostic.memory.pressure
diagnostic.liveness.warning
diagnostic.phase.completed
```

事件记录只保留 allowlist 数值/枚举字段，并增加：

```ts
type OomAgentEventRecord = {
  schemaVersion: 1;
  kind: "agent.runtime.event" | "agent.retention.checkpoint";
  timestamp: string;
  epochMs: number;
  processStartId: string;
  eventType: string;
  runIdHash?: string;
  sessionKeyHash?: string;
  sessionIdHash?: string;
  triggerCategory: "heartbeat" | "wechat" | "api" | "other" | "unknown";
  channelCategory: "openclaw-weixin" | "claw-manager-api" | "other" | "unknown";
  isHeartbeat: boolean;
  outcome?: string;
  durationMs?: number;
  memory: NodeMemorySummary;
  deltaFromRunStart?: NodeMemoryDelta;
  retainedAfterMs?: 60000 | 300000 | 900000;
  details?: Record<string, string | number | boolean>;
};
```

### 3.4 低开销分配采样与安全诊断报告

写入：

```text
/var/lib/openclaw/diagnostics/oom/allocation-profile-<processStartId>-<windowStart>.heapprofile.json
/var/lib/openclaw/diagnostics/oom/process-report-<processStartId>-<threshold>-<timestamp>.json
```

采样规则：

- 使用 `node:inspector` 的本地 `Session` 调用 `HeapProfiler.startSampling`；不调用 `inspector.open()`，不监听任何 TCP 端口。
- `samplingInterval` 默认 `524288` 字节，`stackDepth` 默认 32；每 15 分钟 `stopSampling` 写盘并立即开启下一窗口。
- profile 文件采用临时文件 + rename 原子落盘，单进程最多保留 16 个；写盘失败仅记录限频告警。
- 插件 stop/reload 时必须执行 `stopSampling`、`session.disconnect()`，并清除全局状态；热加载幂等由 `Symbol.for("claw-manager.oom-allocation-profiler")` 保证。
- 采样 profile 只用于定位 JS Heap 分配栈；若 Buffer/ArrayBuffer 的 JS 包装对象被采样，也可辅助定位其创建路径，但不能用它否定 native backing store。
- 每个进程在 cgroup 使用率首次跨过 50%、65%、75% 时调用 `process.report.getReport()`，删除 `environmentVariables`、`commandLine`、用户路径、网络地址和其他敏感字段后再写盘；不得直接调用可能落下未脱敏原始文件的 `process.report.writeReport()`。
- 诊断报告每个阈值最多一份，包含 JS/native stack、libuv handle、heap statistics 和资源信息，用于补足 `activeResources` 只有计数没有调用栈的问题。
- Inspector 不可用、sampling 启动失败或 report 生成失败时，普通 Node/V8 和 Java 外部采集继续运行，不能影响 Gateway。

### 3.5 Agent Runtime 事件安全与保留规则

规则：

- `runId/sessionKey/sessionId` 用实例级随机稳定盐做 SHA-256，只保留 16 个十六进制字符。
- 不保存任意 `event.error`、`event.message`、`reason` 原文；只保存 allowlist 的 `outcome`、`errorCategory`、`failureKind` 等枚举。
- `context.assembled` 只保存 messageCount、字符数、图片数和 token budget。
- `model.call.*` 只保存时长和 request/response 字节数，不保存 provider request ID 原文。
- `diagnostic.heartbeat` 是 OpenClaw 诊断心跳，不等同于 Agent Heartbeat；Agent Heartbeat 仅根据 run/harness 的 trigger 分类。
- run 完成后的 1、5、15 分钟 retained delta 不创建每 run 独立 timer；由 15 秒主采样循环扫描一个最多 200 条的有界 checkpoint 队列。

### 3.6 Java 外部样本扩展

现有 `metrics-YYYY-MM-DD.jsonl` 增加：

```text
processStartId
processStartTicks
bootIdHash
containerStartedAt
containerRestartCount
containerMemoryLimitBytes
containerCurrentOomKilled
cgroupEventOomDelta
cgroupEventOomKillDelta
mapsCollected
mapsTotalCount
mapsAnonymousCount
mapsAnonymousVirtualBytes
mapsExact256KiBAnonymousCount
mapsLe64KiBCount
maps65To128KiBCount
maps129To512KiBCount
maps513KiBTo1MiBCount
maps1To2MiBCount
mapsGt2MiBCount
collectionDurationMs
```

`maps` 每 5 分钟采集一次，其他指标每 15 秒采集。跨进程重启后立即重新采集 maps。

### 3.7 Docker 诊断状态

新增：

```java
public record RuntimeDiagnosticsState(
    String containerId,
    String startedAt,
    long restartCount,
    boolean currentOomKilled,
    long memoryLimitBytes
) {}
```

`OpenClawRuntime` 增加兼容默认实现：

```java
default RuntimeDiagnosticsState inspectDiagnosticsState(InstanceEntity instance) {
  RuntimeState state = inspectInstance(instance);
  return new RuntimeDiagnosticsState("", state.startedAt(), 0, false, 0);
}
```

Docker 实现从 `inspectContainer` 读取真实值。

---

## 4. 根因判定矩阵

自动分析必须使用下表，而不是只看 RSS：

| 证据 | 分类 | 含义 |
|---|---|---|
| `heapUsed`、`old_space.used` 与 RSS 同步线性增长，GC 后基线不回落，Sampling Heap Profile 有稳定高占比分配栈 | `js_heap_retention` | JS 对象被长期引用，并输出主要模块/函数调用栈 |
| `external` 或 `arrayBuffers` 与 RSS 同步增长，Heap 相对稳定；profile 中 Buffer/TypedArray 包装对象栈可作为辅助证据 | `array_buffer_retention` | Buffer/ArrayBuffer/native backing store 被保留 |
| `mallocedMemory` 上升，Heap/external 相对稳定，RSS/anon 上升 | `v8_native_malloc_retention` | V8/native malloc 分配持续保留 |
| Node 内部指标稳定，但 RSS、RssAnon、256 KiB mappings 增长 | `native_or_allocator_retention` | native addon、libuv、OpenClaw C++ 路径或 glibc fragmentation |
| `nativeContexts` 或 `detachedContexts` 持续增长 | `vm_context_retention` | V8 context/realm 未释放 |
| 某个 active resource 类型单调增长 | `resource_handle_leak` | timer/socket/request/watcher 句柄未释放 |
| 多类指标均显著增长 | `mixed` | 多种保留共同存在 |
| 样本不足、跨重启未分段或 R² 过低 | `insufficient_data` | 不允许强行下结论 |

阶段归因规则：

- 每个 `run.started` 记录 before；`run.completed` 记录 immediate after。
- 记录完成后 1/5/15 分钟 retained delta，剔除期间重叠 run；存在重叠时标记 `overlapped=true`，不用于单 run 强归因。
- 分别聚合 Heartbeat、微信、API、other 的 retained MiB/run 和 MiB/hour。
- 分别聚合 context、model、tool、delivery、cleanup 阶段的 immediate delta。
- 若 Heartbeat run 的 retained delta 显著高于无业务空闲窗口，且 Heartbeat 关闭对照段斜率下降至少 70%，才可表述为“Heartbeat 公共路径是主要触发路径”。
- 不得将日志标签 `[agents/tool-policy]` 直接当成泄漏组件。

---

## 5. 实施任务

### Task 1: 拆分普通采集与 Heap Snapshot 安全开关

**Files:**
- Modify: `backend/src/main/java/com/clawbotforall/config/ClawbotProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `compose.yaml`
- Modify: `.env.example`
- Test: `backend/src/test/java/com/clawbotforall/diagnostics/OomDiagnosticsServiceTest.java`
- Test: `backend/src/test/java/com/clawbotforall/runtime/DockerJavaOpenClawRuntimeOpenVikingTest.java`

**Interfaces:**
- Produces: `OomDiagnostics.snapshotEnabled()`、`snapshotTriggerPercent()`、`snapshotMinHeadroomMib()`、`snapshotMinContainerLimitMib()`、`collectionInstanceIds()`、`heapSnapshotInstanceIds()`。

- [ ] **Step 1: 写失败测试，证明普通采集开启时 Heap Snapshot 默认仍关闭。**
- [ ] **Step 2: 写失败测试，证明 2 GiB 容器或剩余 headroom 小于 2 GiB 时绝不发送 `SIGUSR2`。**
- [ ] **Step 3: 将配置记录改为：**

```java
public record OomDiagnostics(
    boolean enabled,
    long intervalMs,
    int retentionDays,
    long metricsLimitMib,
    long minFreeDiskGib,
    List<String> collectionInstanceIds,
    boolean agentEventDiagnosticsEnabled,
    long nodeMetricsIntervalMs,
    long mapsIntervalMs,
    boolean allocationSamplingEnabled,
    long allocationSamplingIntervalBytes,
    long allocationProfileWindowMs,
    int allocationProfileMaxFiles,
    List<Integer> processReportThresholds,
    boolean heapSnapshotEnabled,
    List<String> heapSnapshotInstanceIds,
    double heapSnapshotTriggerPercent,
    long heapSnapshotMinHeadroomMib,
    long heapSnapshotMinContainerLimitMib,
    int heapSnapshotMaxCount,
    long heapSnapshotMaxTotalGib,
    int heapSnapshotPerInstanceMaxCount,
    long heapSnapshotMinIntervalMs
) {}
```

- [ ] **Step 4: 默认值固定为普通采集开启、快照关闭：**

```text
CLAWBOT_OOM_DIAGNOSTICS_ENABLED=true
CLAWBOT_OOM_DIAGNOSTICS_INTERVAL_MS=15000
CLAWBOT_OOM_DIAGNOSTICS_MAPS_INTERVAL_MS=300000
CLAWBOT_OOM_COLLECTION_INSTANCE_IDS=<五个实例>
CLAWBOT_OOM_AGENT_EVENT_DIAGNOSTICS_ENABLED=true
CLAWBOT_OOM_ALLOCATION_SAMPLING_ENABLED=true
CLAWBOT_OOM_ALLOCATION_SAMPLING_INTERVAL_BYTES=524288
CLAWBOT_OOM_ALLOCATION_PROFILE_WINDOW_MS=900000
CLAWBOT_OOM_ALLOCATION_PROFILE_MAX_FILES=16
CLAWBOT_OOM_PROCESS_REPORT_THRESHOLDS=50,65,75
CLAWBOT_OOM_HEAP_SNAPSHOT_ENABLED=false
CLAWBOT_OOM_HEAP_SNAPSHOT_INSTANCE_IDS=
CLAWBOT_OOM_HEAP_SNAPSHOT_TRIGGER_PERCENT=30
CLAWBOT_OOM_HEAP_SNAPSHOT_MIN_HEADROOM_MIB=2048
CLAWBOT_OOM_HEAP_SNAPSHOT_MIN_CONTAINER_LIMIT_MIB=4096
```

- [ ] **Step 5: `snapshotEnabledFor()` 只能在独立开关为 true 且命中 snapshot 白名单时返回 true。**
- [ ] **Step 6: 运行相关 JUnit，确认新测试通过。**
- [ ] **Step 7: 建议提交：`修复：拆分 OOM 采集与安全快照开关`。**

### Task 2: 实现 API Channel 进程内 Node/V8 探针与分配采样

**Files:**
- Create: `plugins/openclaw-api-channel-plugin/src/oom-diagnostics.ts`
- Create: `plugins/openclaw-api-channel-plugin/src/oom-diagnostics.test.ts`
- Create: `plugins/openclaw-api-channel-plugin/src/oom-allocation-profiler.ts`
- Create: `plugins/openclaw-api-channel-plugin/src/oom-allocation-profiler.test.ts`

**Interfaces:**
- Produces:

```ts
export type OomDiagnosticsServiceOptions = {
  env?: NodeJS.ProcessEnv;
  now?: () => number;
  logger?: { info?: (message: string) => void; warn?: (message: string) => void };
};

export function createOomDiagnosticsService(
  options?: OomDiagnosticsServiceOptions,
): {
  id: "claw-manager-api-oom-diagnostics";
  start(ctx: OpenClawPluginServiceContext): Promise<void>;
  stop(ctx: OpenClawPluginServiceContext): Promise<void>;
};
```

- [ ] **Step 1: 写开关关闭测试，断言无 timer、无文件、无监听器。**
- [ ] **Step 2: 写周期样本测试，覆盖 `process.memoryUsage()`、`v8.getHeapStatistics()`、`v8.getHeapSpaceStatistics()`、`process.resourceUsage()` 和 `process.getActiveResourcesInfo()`。**
- [ ] **Step 3: 使用 `monitorEventLoopDelay({ resolution: 20 })` 和 `performance.eventLoopUtilization()`；每个采样窗口读取后 reset histogram。**
- [ ] **Step 4: 使用 `PerformanceObserver` 订阅 `gc` entry，只累计 count/duration/kind，不保存 stack，不触发 GC。**
- [ ] **Step 5: 使用 `process.pid + process.uptime() + instanceId` 生成稳定的 `processStartId`，同一进程不变，重启后变化。**
- [ ] **Step 6: active resources 只按类型计数并限制最多 64 类；过滤任意地址和路径。**
- [ ] **Step 7: 实现单写队列，所有 append 串行；写失败只记一条限频 warning，Promise rejection 不进入业务调用链。**
- [ ] **Step 8: 每日切割，单文件达到 128 MiB 后停止当日追加并写 `limitsHit`，保留 3 天。**
- [ ] **Step 9: 原子写 `node-latest.json`，目录 0700、文件 0600。**
- [ ] **Step 10: 使用 `globalThis[Symbol.for("claw-manager.oom-diagnostics")]` 保存唯一运行状态，热加载/重复 register 不产生重复 timer。**
- [ ] **Step 11: 写失败测试，验证本地 Inspector sampling 每 15 分钟轮换、单进程最多 16 份、stop 时断开连接，且从未调用 `inspector.open()`。**
- [ ] **Step 12: 实现 `OomAllocationProfiler`，调用 `Session.connect()`、`HeapProfiler.enable`、`HeapProfiler.startSampling`；使用 512 KiB sampling interval 和 32 层栈深，profile 以临时文件 + rename 写入。**
- [ ] **Step 13: 每 15 分钟依次 `stopSampling`、保存返回 profile、重新 `startSampling`；禁止两个 sampling session 并存。**
- [ ] **Step 14: 写失败测试，模拟读取 `/sys/fs/cgroup/memory.current` 和 `memory.max`，验证 50%/65%/75% 每个阈值只生成一份脱敏 diagnostic report。**
- [ ] **Step 15: 使用 `process.report.getReport()` 获取对象，在内存中删除 `environmentVariables`、`commandLine`、敏感路径、网络地址、token/Cookie 字段后再落盘；禁止使用 `writeReport()`。**
- [ ] **Step 16: Inspector、cgroup 或 report 能力不可用时写一次 capability warning，普通 Node/V8 采样继续运行。**
- [ ] **Step 17: stop 清理 interval、PerformanceObserver、event-loop histogram、diagnostic listener、Sampling Heap Profiler、Inspector Session 和有界 run map，并等待写队列落盘。**
- [ ] **Step 18: 运行 `npm test -- src/oom-diagnostics.test.ts src/oom-allocation-profiler.test.ts` 和 typecheck。**
- [ ] **Step 19: 建议提交：`诊断：增加 OpenClaw Node/V8 内存与分配路径探针`。**

### Task 3: 关联 OpenClaw 结构化运行阶段事件

**Files:**
- Modify: `plugins/openclaw-api-channel-plugin/src/oom-diagnostics.ts`
- Modify: `plugins/openclaw-api-channel-plugin/src/oom-diagnostics.test.ts`

**Interfaces:**
- Consumes: `ctx.internalDiagnostics.onEvent`。
- Produces: `agent-events-YYYY-MM-DD.jsonl` 和 1/5/15 分钟 retained checkpoints。

- [ ] **Step 1: 写测试覆盖 run、harness、context、model、tool、message delivery 的 allowlist 映射。**
- [ ] **Step 2: 写敏感字段测试，输入包含 message/prompt/error/token/session 原文，断言输出 JSONL 不包含这些值。**
- [ ] **Step 3: 在 service start 中订阅 `ctx.internalDiagnostics?.onEvent`；接口不可用时记录一次 capability warning，但周期 Node 指标继续工作。**
- [ ] **Step 4: 对 `run.started` 保存 run before 内存；对 completed/error 保存 immediate delta。**
- [ ] **Step 5: 以 trigger/channel allowlist 分类 `heartbeat/wechat/api/other/unknown`；不要把 `diagnostic.heartbeat` 误判成 Agent Heartbeat。**
- [ ] **Step 6: 使用有界 run map，最多 200 条；超限按最旧完成时间淘汰，并记录 dropped count。**
- [ ] **Step 7: 在主采样循环生成到期的 1/5/15 分钟 retained checkpoint，不创建额外 timer。**
- [ ] **Step 8: 检测 checkpoint 窗口内是否存在重叠 run，写 `overlapped=true`。**
- [ ] **Step 9: 内部诊断 listener 抛错或输出写失败不得反向影响 OpenClaw diagnostic emitter。**
- [ ] **Step 10: 测试 service stop 后 listener 被取消，run map 清空。**
- [ ] **Step 11: 建议提交：`功能：关联 Agent Runtime 阶段与内存保留量`。**

### Task 4: 在 API Channel 注册独立诊断服务

**Files:**
- Modify: `plugins/openclaw-api-channel-plugin/index.ts`
- Modify: `plugins/openclaw-api-channel-plugin/index.test.ts`

**Interfaces:**
- Consumes: `createOomDiagnosticsService()`。

- [ ] **Step 1: 写失败测试，断言 `register()` 调用一次 `api.registerService()`，service id 为 `claw-manager-api-oom-diagnostics`。**
- [ ] **Step 2: 在 plugin register 中注册 service，不改变 API queue monitor 和 assistant event bridge。**
- [ ] **Step 3: 验证 registrationMode 下重复加载不会生成第二个运行探针。**
- [ ] **Step 4: 运行 API Channel 全量 `npm test && npm run typecheck && npm run build`。**
- [ ] **Step 5: 建议提交：`集成：注册 API Channel OOM 诊断服务`。**

### Task 5: 加强 Java `/proc`、cgroup 和 Docker 生命周期采集

**Files:**
- Create: `backend/src/main/java/com/clawbotforall/runtime/RuntimeDiagnosticsState.java`
- Create: `backend/src/main/java/com/clawbotforall/diagnostics/ProcMapsSummary.java`
- Create: `backend/src/test/java/com/clawbotforall/diagnostics/ProcMapsSummaryTest.java`
- Modify: `backend/src/main/java/com/clawbotforall/runtime/OpenClawRuntime.java`
- Modify: `backend/src/main/java/com/clawbotforall/runtime/DockerJavaOpenClawRuntime.java`
- Modify: `backend/src/main/java/com/clawbotforall/diagnostics/OomDiagnosticsService.java`
- Modify: `backend/src/main/java/com/clawbotforall/diagnostics/OomDiagnosticsScheduler.java`
- Modify: related JUnit tests

**Interfaces:**
- Produces: 扩展后的 `metrics-YYYY-MM-DD.jsonl`。

- [ ] **Step 1: 为 `ProcMapsSummary` 写 fixture，覆盖无 pathname 匿名映射、`[anon:*]`、文件映射和精确 256 KiB。**
- [ ] **Step 2: 实现容器内只输出聚合计数的 maps 脚本；禁止把完整 maps 地址/path 回传或落盘。**
- [ ] **Step 3: 读取 `/proc/<pid>/stat` starttime 和 boot ID，计算 `processStartId`。**
- [ ] **Step 4: 在内存中按 instanceId/processStartId 保存最近一次 maps 采集时间，默认每 300 秒采集；进程变化立即采集。**
- [ ] **Step 5: 为每个 processStartId 保存上一轮 cgroup oom/oom_kill 计数，输出绝对值和 delta。**
- [ ] **Step 6: Docker runtime 实现 `inspectDiagnosticsState()`，读取 container id、startedAt、restartCount、current OOMKilled 和 memory limit。**
- [ ] **Step 7: 样本增加采集耗时；单实例 maps 超时只跳过 maps，不丢失本轮基础样本。**
- [ ] **Step 8: 保持五实例串行或小并发（最多 2），但不允许上一轮未完成时叠加新一轮。**
- [ ] **Step 9: 测试一个实例失败不阻止其他实例，命令输出中的未知字段不会落盘。**
- [ ] **Step 10: 运行 diagnostics/runtime 相关 JUnit。**
- [ ] **Step 11: 建议提交：`功能：增强容器匿名映射与重启生命周期采集`。**

### Task 6: 修复 Heap Snapshot 触发和失败文件处理

**Files:**
- Modify: `backend/src/main/java/com/clawbotforall/diagnostics/OomDiagnosticsService.java`
- Modify: `backend/src/test/java/com/clawbotforall/diagnostics/OomDiagnosticsServiceTest.java`

**Interfaces:**
- Consumes: `RuntimeDiagnosticsState.memoryLimitBytes()` 和安全快照配置。

- [ ] **Step 1: 写测试证明 0 字节 `.heapsnapshot` 不计入数量/容量，且超过失败等待时间后被删除。**
- [ ] **Step 2: 写测试证明只有文件大小稳定且至少 1 MiB 才计为成功快照。**
- [ ] **Step 3: `shouldScheduleHeapSnapshot()` 同时校验：独立开关、实例白名单、内存比例、容器 limit >= 4 GiB、headroom >= 2 GiB、Node signal ready、磁盘、全局锁和最小间隔。**
- [ ] **Step 4: 删除硬编码 `SNAPSHOT_MEMORY_PERCENT = 82.0`，改为配置值。**
- [ ] **Step 5: 快照触发前写不含敏感内容的 attempt 审计；失败后写 exit/result，不自动循环重试。**
- [ ] **Step 6: wait 超时、进程退出或文件持续为 0 时删除失败文件。**
- [ ] **Step 7: 默认部署配置保持 snapshot disabled。**
- [ ] **Step 8: 建议提交：`修复：避免 Heap Snapshot 再次触发容器 OOM`。**

### Task 7: 实现自动 OOM 归因分析器

**Files:**
- Create: `scripts/analyze-openclaw-oom.mjs`
- Create: `scripts/tests/analyze-openclaw-oom.test.mjs`
- Create: `scripts/tests/fixtures/oom/*`

**Interfaces:**
- Command:

```bash
node scripts/analyze-openclaw-oom.mjs \
  --root data/instances \
  --instances mskgjut8-696d76,mskgjwgm-cfd552,mskgk1at-0f66eb,mskgk7u2-b24856,mskgk332-da8bdb \
  --from 2026-08-28T12:00:00Z \
  --to 2026-08-29T04:00:00Z \
  --output outputs/oom-analysis-2026-08-29
```

- Outputs:

```text
summary.md
per-instance.csv
process-segments.csv
run-deltas.csv
resource-trends.csv
allocation-sites.csv
classification.json
```

- [ ] **Step 1: 写 JS Heap fixture：heapUsed/old space 增长，期望 `js_heap_retention`。**
- [ ] **Step 2: 写 ArrayBuffer fixture：external/arrayBuffers 增长，期望 `array_buffer_retention`。**
- [ ] **Step 3: 写 native fixture：Node 内部稳定、RSS/anon/256 KiB maps 增长，期望 `native_or_allocator_retention`。**
- [ ] **Step 4: 写 active resource fixture，期望 `resource_handle_leak` 并输出增长资源类型。**
- [ ] **Step 5: 写跨重启 fixture，断言不同 processStartId 分段拟合，不跨段计算 slope。**
- [ ] **Step 6: 实现 JSONL 容错读取：最后一行被截断时跳过并计数，不使整次分析失败。**
- [ ] **Step 7: 对每个指标计算 slope/hour、R²、样本数、开始/结束值、最大值。**
- [ ] **Step 8: 对 run 计算 immediate 和 1/5/15 分钟 retained delta，重叠 run 单独标记。**
- [ ] **Step 9: 按 trigger/category/phase 聚合 retained MiB/run 和 MiB/hour。**
- [ ] **Step 10: 解析 Sampling Heap Profile，按 URL/函数/调用栈聚合 `selfSize` 和样本占比，过滤 Node 内部噪声后输出 `allocation-sites.csv`；不得读取或输出对象内容。**
- [ ] **Step 11: 将 profile 时间窗口与 run/phase 时间线对齐，输出每种 trigger/phase 窗口内排名前 20 的分配栈。**
- [ ] **Step 12: 使用第 4 节矩阵生成 classification、confidence 和 supportingEvidence；JS/Buffer 类结论需附主要分配栈，native 类结论需附 Node 指标差值、maps bucket 和 process report 证据。**
- [ ] **Step 13: `summary.md` 固定包含：OOM 事件、内存类型、增长最快实例、Heartbeat 对比、阶段排行、句柄排行、分配栈排行、下一步建议。**
- [ ] **Step 14: 运行 `node --test scripts/tests/analyze-openclaw-oom.test.mjs`。**
- [ ] **Step 15: 建议提交：`工具：增加 OpenClaw OOM 自动归因分析器`。**

### Task 8: 更新配置注入、文档和 npm 版本

**Files:**
- Modify: `backend/src/main/java/com/clawbotforall/runtime/DockerJavaOpenClawRuntime.java`
- Modify: `backend/src/test/java/com/clawbotforall/runtime/DockerJavaOpenClawRuntimeOpenVikingTest.java`
- Modify: `.env.example`
- Modify: `compose.yaml`
- Modify: `docs/oom-diagnostics.md`
- Modify: `plugins/openclaw-api-channel-plugin/package.json`
- Modify: `plugins/openclaw-api-channel-plugin/openclaw.plugin.json`

**Interfaces:**
- Produces: 五个 Runner 创建时一致的诊断 env。

- [ ] **Step 1: 测试 runner env 包含 Node 指标 interval、retention、instanceId、Agent event、allocation sampling、profile window 和 process report thresholds。**
- [ ] **Step 2: 测试 snapshot disabled 时 `NODE_OPTIONS` 不包含 `--heapsnapshot-signal`，但保留 `--max-old-space-size=1536`。**
- [ ] **Step 3: 测试只有 snapshot enabled 且实例命中白名单时才注入 signal/diagnostic-dir。**
- [ ] **Step 4: `.env.example` 和 compose 将五实例普通采集打开，将 Heap Snapshot 默认关闭。**
- [ ] **Step 5: 文档明确停止使用“`OOMKilled=false` 表示没有历史 OOM”的错误判断。**
- [ ] **Step 6: 文档写明 0 字节 snapshot 是失败产物，不能直接删除后继续用旧阈值重试。**
- [ ] **Step 7: 发布前执行 `npm view @claw-manager/openclaw-api-channel version`，将 package 和 manifest 更新为下一个未占用版本。**
- [ ] **Step 8: 建议提交：`文档：完善 OOM 诊断部署与次日分析流程`。**

### Task 9: 全量验证

**Files:**
- No production code changes.

- [ ] **Step 1: 后端：**

```powershell
cd D:\code\daxiangmu\claw-manager\backend
mvn test
```

- [ ] **Step 2: API Channel：**

```powershell
cd D:\code\daxiangmu\claw-manager\plugins\openclaw-api-channel-plugin
npm test
npm run typecheck
npm run build
npm pack --dry-run
```

- [ ] **Step 3: 分析器：**

```powershell
cd D:\code\daxiangmu\claw-manager
node --test scripts/tests/analyze-openclaw-oom.test.mjs
```

- [ ] **Step 4: Compose：**

```powershell
docker compose config --quiet
docker compose -f compose.yaml -f compose.local.yaml config --quiet
```

- [ ] **Step 5: 检查 git diff，确认未改动基础镜像、Dockerfile 和 entrypoint。**
- [ ] **Step 6: 建议最终提交：`诊断：建立 OpenClaw OOM 根因采集与自动分析链路`。**

---

## 6. 生产部署方案（2026 年 8 月 28 日晚）

### 6.1 发布物

1. 发布新的 `@claw-manager/openclaw-api-channel` npm 包。
2. GitHub 构建并发布新的 `claw-manager-api` 镜像。
3. 不发布 Runner 镜像。
4. Web 无功能改动时无需专门构建，但用户现有整套 compose pull 流程可以继续使用。

### 6.2 `.env` 推荐值

```env
CLAWBOT_OOM_DIAGNOSTICS_ENABLED=true
CLAWBOT_OOM_DIAGNOSTICS_INTERVAL_MS=15000
CLAWBOT_OOM_DIAGNOSTICS_MAPS_INTERVAL_MS=300000
CLAWBOT_OOM_DIAGNOSTICS_RETENTION_DAYS=3
CLAWBOT_OOM_DIAGNOSTICS_METRICS_LIMIT_MIB=256
CLAWBOT_OOM_DIAGNOSTICS_MIN_FREE_DISK_GIB=30
CLAWBOT_OOM_COLLECTION_INSTANCE_IDS=mskgjut8-696d76,mskgjwgm-cfd552,mskgk1at-0f66eb,mskgk7u2-b24856,mskgk332-da8bdb
CLAWBOT_OOM_AGENT_EVENT_DIAGNOSTICS_ENABLED=true
CLAWBOT_OOM_NODE_METRICS_INTERVAL_MS=15000
CLAWBOT_OOM_NODE_METRICS_RETENTION_DAYS=3
CLAWBOT_OOM_NODE_METRICS_MAX_FILE_MIB=128
CLAWBOT_OOM_MAX_TRACKED_RUNS=200
CLAWBOT_OOM_ALLOCATION_SAMPLING_ENABLED=true
CLAWBOT_OOM_ALLOCATION_SAMPLING_INTERVAL_BYTES=524288
CLAWBOT_OOM_ALLOCATION_PROFILE_WINDOW_MS=900000
CLAWBOT_OOM_ALLOCATION_PROFILE_MAX_FILES=16
CLAWBOT_OOM_PROCESS_REPORT_THRESHOLDS=50,65,75
CLAWBOT_OOM_HEAP_SNAPSHOT_ENABLED=false
CLAWBOT_OOM_HEAP_SNAPSHOT_INSTANCE_IDS=
CLAWBOT_OOM_HEAP_SNAPSHOT_TRIGGER_PERCENT=30
CLAWBOT_OOM_HEAP_SNAPSHOT_MIN_HEADROOM_MIB=2048
CLAWBOT_OOM_HEAP_SNAPSHOT_MIN_CONTAINER_LIMIT_MIB=4096
```

### 6.3 部署顺序

1. 使用现有方式更新管理服务：

```bash
docker compose down
docker compose pull
docker compose up -d
```

2. 确认 API 容器读取新变量。
3. 先选择 `mskgk7u2-b24856` 做 5 分钟 canary：升级 API Channel 插件并停止/启动该实例，确认 Gateway 正常、CPU 增量低于 2%、诊断目录持续写入且没有 inspector 监听端口；异常时立即关闭 allocation sampling 独立开关。
4. canary 通过后，在管理台把其余四个实例的 API Channel 插件升级到新 npm 版本。
5. 对其余四个实例分别执行“停止实例”后“启动实例”。仅重启 Gateway 不足以更新 Runner 环境变量。
6. 五个实例 ID 保持不变：

```text
mskgjut8-696d76
mskgjwgm-cfd552
mskgk1at-0f66eb
mskgk7u2-b24856
mskgk332-da8bdb
```

7. 启动 2–3 分钟后确认每个实例存在：

```text
node-latest.json
node-metrics-YYYY-MM-DD.jsonl
agent-events-YYYY-MM-DD.jsonl
memory-latest.json
metrics-YYYY-MM-DD.jsonl
```

8. 检查 `node-latest.json` 的 `processStartId`、heap spaces、external、arrayBuffers、activeResources、GC、eventLoop 字段非空。
9. 运行至少 16 分钟，确认出现非 0 字节 `allocation-profile-*.heapprofile.json`，且 profile 可被分析器读取；检查进程没有开放新的 inspector 端口。
10. 检查 `agent-events` 至少出现周期 run；不得出现消息正文和原始 session ID。
11. 检查五个 2 GiB 实例的 `NODE_OPTIONS` 不再含 `--heapsnapshot-signal=SIGUSR2`。

### 6.4 可选受控 Heap Snapshot（只允许一个实例）

普通夜间采集足以确定内存类型和运行阶段。只有在必须获得 JS dominator 对象名，且确认 `heapUsed/old_space` 明显增长时，才启用以下受控方案：

1. 选择增长最快的 `mskgk332-da8bdb`。
2. 在该实例完成停止/启动后，将容器上限临时提升为 4 GiB：

```bash
docker update --memory 4g --memory-swap 5g clawbot-openclaw-mskgk332-da8bdb
```

3. 仅将该实例加入 snapshot 白名单，并开启 snapshot 开关；重新创建 API 容器和该 Runner，使 signal env 生效。
4. 触发门槛为 30%，且必须同时满足可用 headroom >= 2 GiB。
5. 全局最多一次；其他四个实例严格禁用。
6. 若无法满足 4 GiB limit 和 2 GiB headroom，宁可不生成，不得降级回 82% 触发。
7. Snapshot 可能包含业务字符串，只在服务器本地受控分析。

默认建议：第一晚先禁用所有 Snapshot，避免诊断器再次成为事故触发器；次日指标若明确为 JS Heap，再在受控实例执行一次。

---

## 7. Heartbeat A/B 方案

为区分“后台空闲增长”和“Heartbeat 触发的 Agent Runtime 增长”，选择低流量实例：

```text
mskgk7u2-b24856
```

推荐窗口：

- 2026-08-28 晚部署后至次日 02:00：Heartbeat 保持现状。
- 次日 02:00–08:00：只关闭该实例 Heartbeat，其他配置不变。
- 微信、API、OpenViking、模型和插件均不同时调整。

注意：

- 不让诊断插件自行改业务配置。
- 如果无法安全定时切换，则整晚保持 Heartbeat 开启，分析器仍通过 run-level retained delta 归因，但 A/B 置信度会降低。
- Heartbeat 不应影响普通 API 或微信记忆抽取/召回；本次 A/B 只用于一个低流量实例，且 OpenViking 当前已出现 quarantine/legacy fallback，分析时必须单独注明这一运行状态。

---

## 8. 次日分析流程（2026 年 8 月 29 日）

1. 不先重启实例，先保存五个实例 diagnostics 目录和对应时间段 Gateway 日志。
2. 记录宿主机 kernel journal 中新的 cgroup OOM 时间；不使用容器重启后的 `.State.OOMKilled=false` 否定历史 OOM。
3. 执行自动分析器，按 `processStartId` 分段。
4. 首先查看 `classification.json`：每实例分类、置信度和证据。
5. 查看 `per-instance.csv`：RSS、Heap、external、ArrayBuffer、malloc、anonymous、256 KiB maps 的斜率与 R²。
6. 查看 `run-deltas.csv`：Heartbeat、微信、API 的 immediate/1m/5m/15m retained delta。
7. 查看 `resource-trends.csv`：Timeout、TCP、FS、Promise 等资源类型是否增长。
8. 查看 `allocation-sites.csv`：确认增长窗口中排名最高的包、文件、函数和调用栈，并与 run/phase 对齐。
9. 查看 50%/65%/75% 脱敏 process report 中的 native stack、libuv handles 和 heap statistics。
10. 对齐 OpenViking `TURN_IDENTITY_CHANNEL_MISMATCH` 时间，比较 quarantine 前后斜率。
11. 输出最终结论时必须使用以下格式：

```text
直接 OOM 触发器：...
长期内存类型：...
主要持有者：OpenClaw PID 1 / JS Heap / ArrayBuffer / native allocator / active resource...
主要触发运行：Heartbeat / 微信 / API / 空闲后台...
主要阶段：context / model / tool / delivery / cleanup...
证据：指标 A 斜率、指标 B retained delta、A/B 变化、OOM 时间线...
置信度：高 / 中 / 低
下一步代码修复范围：具体插件/模块；若 native 仍需 native profiler，则明确说明。
```

### 成功标准

部署一夜后至少达到以下一级定位，不再停留在“Node 内存上涨”：

- 明确内存归属类别；
- 明确增长最快的 run 类型；
- 明确增长集中阶段；
- 明确是否存在资源句柄或 V8 context 泄漏；
- 明确 Heap Snapshot 是否仍参与 OOM（默认应为否）；
- 明确 Heartbeat A/B 对增长斜率的影响。

若结果为 `native_or_allocator_retention`，本轮仍已确定为“Node Heap/external 之外的 native/allocator 匿名映射保留”，并能定位到触发 run/阶段；下一步才需要在一个隔离实例使用 native profiler 获取 C/C++ allocation stack。不能将这种情况表述为“仍不知道是谁持有内存”。

---

## 9. 风险与回滚

### 风险控制

- 插件指标采样频率 15 秒，所有普通操作为本进程读取和小型 JSON append；maps 重采样由后端每 5 分钟完成。Sampling Heap Profiler 使用 512 KiB 间隔、15 分钟滚动窗口，并可通过独立开关关闭。
- 不为每个 run 创建 timer，避免采集器自身产生 Timeout 泄漏。
- bounded run map 最大 200，active resource 类型最大 64，单日文件最大 128 MiB。
- 写失败限频，不进入业务 Promise 链。
- 默认不生成 Heap Snapshot。

### 回滚

出现任何异常时只需：

```env
CLAWBOT_OOM_DIAGNOSTICS_ENABLED=false
CLAWBOT_OOM_AGENT_EVENT_DIAGNOSTICS_ENABLED=false
CLAWBOT_OOM_ALLOCATION_SAMPLING_ENABLED=false
CLAWBOT_OOM_HEAP_SNAPSHOT_ENABLED=false
```

重新创建 API 容器，并停止/启动五个实例以移除 Runner env。禁用诊断不会删除既有采集文件，也不会改变微信、Agent、workspace 或 OpenViking 记忆。

---

## 10. 计划自检结果

- 覆盖了当前诊断器直接触发 OOM 的安全缺陷。
- 覆盖 Node Heap、external/ArrayBuffer、native malloc、匿名映射、V8 context、active resource、GC 和事件循环。
- 覆盖本地无端口 Sampling Heap Profile、主要分配调用栈和脱敏 process report。
- 覆盖 Heartbeat/微信/API 及 context/model/tool/delivery 阶段关联。
- 覆盖跨重启分段和 cgroup OOM delta。
- 覆盖自动分析和确定性判定矩阵。
- 覆盖五实例部署、环境变量生效、文件生成检查和次日分析。
- 明确不修改基础镜像、Dockerfile、entrypoint。
- 未包含消息正文、凭据或原始用户身份采集。
