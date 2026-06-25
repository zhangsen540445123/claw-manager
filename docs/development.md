# 本地开发

## 基础要求

- JDK 21
- Node.js 22+
- Docker Desktop 或 Docker Engine
- 本机允许执行 `docker pull`、`docker run`、`docker exec`、`docker logs`

## 启动基础服务

```powershell
docker compose up -d mysql redis
```

## 启动后端

```powershell
cd backend
mvn spring-boot:run
```

默认 API 地址：

```text
http://127.0.0.1:8080
```

## 启动前端

```powershell
cd frontend
npm ci
npm run dev
```

默认 Vite 地址：

```text
http://127.0.0.1:5173
```

## 本地源码构建容器

`compose.local.yaml` 只覆盖 API 和 Web 的 build 配置。需要显式指定才会从源码构建：

```powershell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## 常用验证

```powershell
cd backend
mvn test

cd ..\frontend
npm run build

cd ..
docker compose config --quiet
docker compose -f compose.yaml -f compose.local.yaml config --quiet
```

## 开发约定

- 不要因为历史命名不美观就顺手重命名 `clawbot`、`clawbotforall` 相关 package、数据库名或配置 key。
- MySQL 是业务状态事实源，Redis 不作为持久业务状态源。
- OpenViking 配置只从后台“OpenViking预设”管理，不写入 `application.yml` 或 `compose.yaml`。
- 插件包代码在 `plugins/` 下维护，仓库级文档只描述 Claw Manager 如何集成它们。
