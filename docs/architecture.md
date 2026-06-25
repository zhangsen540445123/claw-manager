# 项目架构

Claw Manager 是 OpenClaw 的管理台，不替代 OpenClaw Gateway，也不直接执行模型推理。它负责创建、配置、启动、停止和观测 OpenClaw 实例，并把微信插件、OpenViking 插件和模型预设以管理员后台的方式串起来。

## 组件边界

| 组件 | 职责 |
| --- | --- |
| Web | Vue 管理后台，承载登录、模型预设、OpenViking预设、创建实例、插件管理、用户中心和日志查看 |
| API | Spring Boot 后端，负责鉴权、实例管理、插件安装、Docker runtime、OpenViking broker 和 WebSocket 状态推送 |
| MySQL | 业务状态事实源，包括管理员、实例、模型预设、微信绑定、OpenViking 设置和 user key 缓存 |
| Redis | 随编排提供，用于运行时能力，不作为持久业务状态事实源 |
| Runner | 每个 OpenClaw 实例对应的运行容器，运行 OpenClaw Gateway 和已安装插件 |
| OpenClaw Gateway | OpenClaw 的实际会话、Control UI、插件执行和上下文生命周期入口 |
| OpenViking Server | 独立部署的长期记忆服务，Claw Manager 只保存配置和 root key，不承载记忆存储 |

## 后端模块

| 模块 | 职责 |
| --- | --- |
| `auth` | Cookie Session、管理员初始化、首次登录强制改密 |
| `model` | 模型 Provider、模型预设、默认预设、实例模型链 |
| `instance` | OpenClaw 实例创建、启动、停止、Gateway provisioning、运行统计 |
| `wechat` | 微信扫码绑定、多账号读取、备注、解绑 |
| `openviking` | OpenViking预设、身份盐值、root key、user key broker、插件安装管理 |
| `runtime` | docker-java 封装镜像、容器、exec、logs、stats |
| `proxy` | OpenClaw Control UI HTTP/WebSocket 代理 |
| `ws` | STOMP Endpoint、管理员 topic、入站鉴权 |

## 状态与推送

REST 负责命令类操作，WebSocket/STOMP 负责管理员状态刷新。管理员 topic 包括：

- `/topic/admin/instances`
- `/topic/admin/instance-stats`
- `/topic/admin/wechat`
- `/topic/admin/model-auth`
- `/topic/admin/runner-image`

STOMP CONNECT、SUBSCRIBE、SEND 都需要已登录管理员主体。前端断线重连后会通过 REST 做一次全量校准。

## 数据事实源

MySQL 是业务状态事实源。Runner 容器内的 OpenClaw 数据、微信账号状态和 workspace 文件保存在挂载目录 `./data` 下；OpenViking 记忆保存在外部 OpenViking Server。
