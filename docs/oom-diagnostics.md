# OOM 诊断采集说明

## 用途

OOM 诊断采集用于定位 OpenClaw 实例内存持续增长、容器 cgroup OOM 和 Node 主进程 RSS/PSS 占用来源。本次部署通过配置开关开启，并限定普通指标采集与 Heap Snapshot 均只覆盖以下五个实例：

- `mskgjut8-696d76`
- `mskgjwgm-cfd552`
- `mskgk1at-0f66eb`
- `mskgk7u2-b24856`
- `mskgk332-da8bdb`

采集只记录资源统计、cgroup 计数和脱敏后的进程资源信息，不记录消息正文、会话正文、提示词、文件内容、账号标识或认证信息。

## 配置开关

API 配置：

```env
CLAWBOT_OOM_DIAGNOSTICS_ENABLED=true
```

关闭采集：

```env
CLAWBOT_OOM_DIAGNOSTICS_ENABLED=false
```

其他默认值：

```env
CLAWBOT_OOM_DIAGNOSTICS_INTERVAL_MS=30000
CLAWBOT_OOM_DIAGNOSTICS_RETENTION_DAYS=7
CLAWBOT_OOM_DIAGNOSTICS_METRICS_LIMIT_MIB=256
CLAWBOT_OOM_DIAGNOSTICS_MIN_FREE_DISK_GIB=30
CLAWBOT_OOM_HEAP_SNAPSHOT_MAX_COUNT=5
CLAWBOT_OOM_HEAP_SNAPSHOT_MAX_TOTAL_GIB=12
CLAWBOT_OOM_HEAP_SNAPSHOT_PER_INSTANCE_MAX_COUNT=1
CLAWBOT_OOM_HEAP_SNAPSHOT_MIN_INTERVAL_MS=600000
```

其中 `CLAWBOT_OOM_HEAP_SNAPSHOT_INSTANCE_IDS` 同时作为本次诊断采集实例白名单；空列表表示采集所有运行实例。JSONL 大小限制按实例计算；Heap Snapshot 受全局总数、总容量、单实例数量、磁盘可用空间和最小间隔共同限制。Heap Snapshot 只在容器内存使用率达到阈值且 Node 进程明确启用信号参数时触发，五个实例之间全局串行，不会并发生成。

## 部署后生效方式

修改 `.env` 后需要重新创建 API 容器，不能只执行 `restart`：

```bash
docker compose up -d --build --force-recreate api
```

确认 API 开关和五实例白名单：

```bash
docker exec claw-manager-api printenv CLAWBOT_OOM_DIAGNOSTICS_ENABLED
docker exec claw-manager-api printenv CLAWBOT_OOM_HEAP_SNAPSHOT_INSTANCE_IDS
```

Heap Snapshot 相关的 `NODE_OPTIONS` 是创建 Runner 容器时注入的。API 重建不会改变已存在 Runner 容器的环境，因此需要通过管理台对五个实例分别执行停止后启动，确保容器被重新创建。不要修改 Runner 基础镜像，也不需要修改 `entrypoint.sh`。

检查某个 Runner：

```bash
docker inspect clawbot-openclaw-<instanceId> \
  --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -E 'CLAW_MANAGER_OOM|NODE_OPTIONS'
```

## 输出位置

诊断文件位于 API 数据目录对应的实例目录：

```text
data/instances/<instanceId>/home/diagnostics/oom/
```

常见文件：

```text
memory-latest.json
metrics-YYYY-MM-DD.jsonl
snapshots/*.heapsnapshot
```

`memory-latest.json` 保存最近一次采样；JSONL 保存历史采样；Heap Snapshot 可能包含进程堆中的业务字符串，必须按高度敏感文件保护，不要上传到不可信位置或直接发给第三方。

## 采集内容

每次采样尽量记录：

- Docker stats 的 CPU、内存使用、内存百分比、网络 I/O、PIDs。
- Node/OpenClaw 进程的 VmRSS、匿名内存、文件映射、Swap、PSS、Private Dirty。
- cgroup v2 的当前内存、Swap、OOM/OOM kill 次数、anon/file/kernel/slab/shmem 和缺页计数。
- 容器内最多 48 个进程的 PID、PPID、进程名和 RSS，进程名经过白名单清洗。

采集失败只影响当前诊断样本，不会中断 API、微信消息、Heartbeat 或 OpenViking 记忆流程。

## 第二天分析

重点比较 `metrics-YYYY-MM-DD.jsonl` 中的时间序列：

1. `memoryPercent` 与 `cgroupMemoryCurrentBytes` 是否同步增长。
2. `vmRssKib`、`rssAnonKib`、`pssKib` 是否主要由 Node 进程增长。
3. `cgroupFileBytes` 是否增长而匿名内存稳定，判断是否主要是文件缓存。
4. `cgroupEventOom`、`cgroupEventOomKill` 是否在重启前递增。
5. `processes` 中是否出现文档解析、浏览器或其他子进程持续占用内存。
6. Heap Snapshot 是否在达到阈值后生成，以及生成时间是否与重启时间接近。

不要删除诊断目录、core 文件或用户 workspace；诊断结束后再按保留策略处理 Heap Snapshot。
