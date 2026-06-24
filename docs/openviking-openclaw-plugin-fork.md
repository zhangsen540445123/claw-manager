# OpenViking OpenClaw 插件二开说明

本仓库不直接在 runner 里安装 OpenViking 官方示例插件，而是把官方源码 vendoring 到
`plugins/openviking-openclaw-plugin` 后进行二开。这样可以在同一个 OpenViking
`account_id` 下，按微信真实发送者动态派生 `user_id`，并保证召回、注入、写入和工具查询都使用
sender-scoped client。

## 官方源码获取

官方源码位置：

- GitHub: `https://github.com/volcengine/OpenViking`
- 插件源码目录：`examples/openclaw-plugin`

刷新插件源码时使用独立临时目录，先记录 upstream commit，再复制源码：

```powershell
git clone https://github.com/volcengine/OpenViking.git D:\tmp\OpenViking
git -C D:\tmp\OpenViking rev-parse HEAD

Remove-Item -Recurse -Force D:\code\daxiangmu\claw-manager\plugins\openviking-openclaw-plugin
Copy-Item -Recurse `
  D:\tmp\OpenViking\examples\openclaw-plugin `
  D:\code\daxiangmu\claw-manager\plugins\openviking-openclaw-plugin
```

复制后需要重新应用本仓库的二开改动，并在 `package.json` 和 `install-manifest.json` 中保留私有包版本。

## 二开入口

本仓库插件包路径：

- `plugins/openviking-openclaw-plugin`

关键改造点：

- `identity.ts`：根据 `senderId` 和 `OPENVIKING_IDENTITY_HASH_SECRET` 派生稳定的
  `wx_<hmac32>` OpenViking user id。
- `client.ts`：`OpenVikingClient.withUser(userId)` 为每次请求动态设置
  `X-OpenViking-User`。
- `plugin/openviking-client-runtime.ts`：在配置了 identity secret 时启用 sender-scoped
  client。
- `services/context-lifecycle-service.ts`：自动召回、上下文注入、session 写入和 commit 都按
  当前 sender 获取 client；缺少 sender 时跳过用户记忆能力。
- `plugin/openviking-runtime-utils.ts`：工具调用优先从 `requesterSenderId`，其次从
  `senderId` 解析身份；解析不到时拒绝执行用户记忆工具。
- `plugin/openviking-*-tools.ts`、`plugin/openviking-query-runtime.ts`、slash command
  定义：所有读写类 OpenViking API 都需要走 sender-scoped client。

## runner 安装方式

runner 镜像构建时把插件源码复制进镜像并构建：

```dockerfile
COPY plugins/openviking-openclaw-plugin /opt/openviking-openclaw-plugin
RUN cd /opt/openviking-openclaw-plugin \
    && npm ci \
    && npm run build
```

运行时通过环境变量指定安装源：

```text
OPENVIKING_PLUGIN_PACKAGE=file:/opt/openviking-openclaw-plugin
```

`containers/openclaw-runner/entrypoint.sh` 会执行：

```sh
openclaw plugins install "${OPENVIKING_PLUGIN_PACKAGE}" --force
```

如需改成私有 npm 包或 tarball，保持 `OPENVIKING_PLUGIN_PACKAGE` 指向对应安装源即可。

## claw-manager 注入配置

后端创建 OpenClaw runner 容器时注入：

- `OPENVIKING_BASE_URL`
- `OPENVIKING_TRUSTED_MODE_ENABLED`
- `OPENVIKING_ACCOUNT_ID`
- `OPENVIKING_IDENTITY_HASH_SECRET`
- `OPENVIKING_OPENCLAW_INSTANCE_ID`
- `OPENVIKING_PLUGIN_PACKAGE`

`OPENVIKING_IDENTITY_HASH_SECRET` 由 claw-manager 统一生成并持久化到
`data/openviking/identity-hash-secret`。多 OpenClaw 实例必须共享这个 secret，否则同一个微信
sender 会派生出不同的 OpenViking user id。

## 验证命令

```powershell
cd plugins/openviking-openclaw-plugin
npm run typecheck
npm run build
npm test -- tests/ut/sender-identity.test.ts tests/ut/client-runtime-sender-scope.test.ts tests/ut/context-lifecycle-sender-scope.test.ts tests/ut/sender-scoped-tools.test.ts

cd ..\..\backend
mvn test '-Dtest=*,!ApplicationIntegrationTest'

cd ..
docker compose config --quiet
docker compose -f compose.yaml -f compose.local.yaml config --quiet
```

Docker daemon 可用时再验证 runner 镜像构建：

```powershell
docker build -f containers/openclaw-runner/Dockerfile . `
  --build-arg OPENCLAW_VERSION=2026.6.8 `
  -t claw-manager-openclaw-runner:openviking-plugin-test
```
