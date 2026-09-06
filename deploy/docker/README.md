# 星笺 STELLAR INK Docker 部署手册

Docker Compose 一键编排：**Nacos + 网关 + 6 个微服务 + 前端 Nginx**。
所有 Java 服务走 `prod` profile，敏感配置统一放同目录 `.env`。

## 一、与服务器已有容器的关系

服务器上已独立运行（**本编排不创建、不接管**，请勿用 compose 重建）：

| 容器 | 端口 | 与本部署的关系 |
|---|---|---|
| mysql | 3306 | **现在就用到**。业务服务经 `host.docker.internal`（host-gateway）访问宿主机 3306 |
| redis | 6379 | 暂未使用（将来接入限流/会话时再纳入 compose 网络） |
| qdrant | 6333-6334 | 暂未使用（AI 能力预留） |

本编排启动的容器：

| 服务 | 容器端口 | 对外暴露 | 说明 |
|---|---|---|---|
| nacos | 8848 / 9848 | 仅宿主机 `127.0.0.1` | 注册中心 + 配置中心（standalone，数据落 volume） |
| gateway | 8080 | `GATEWAY_PORT`（默认 8080） | 后端唯一入口 |
| user/post/meteor/echo/link/stats | 8101-8106 | 不暴露 | 内部网络经 Nacos 服务发现互相调用 |
| web（nginx） | 80 | `WEB_PORT`（默认 80） | 前端静态资源 + `/posts` 等 API 前缀反代到网关 |

```
浏览器 ──▶ web(:80)──静态 SPA；/auth|/posts|/meteors|/echos|/links|/stats… ──▶ gateway(:8080)
                                                                     gateway ──▶ 各业务服务(8101-8106)
全部服务 ──▶ nacos(:8848，注册 + 配置)          业务服务 ──▶ 宿主机 3306（已有 mysql 容器）
```

## 二、首次部署

> 要求：Docker 20.10+（`host-gateway` 支持）与 Docker Compose v2；服务器内存建议 ≥ 4G。

### 1. 配置环境变量（必做）

```bash
cd deploy/docker
cp .env.example .env
vi .env
```

必填两项：`MYSQL_PASSWORD`、`SA_TOKEN_JWT_SECRET`（`openssl rand -base64 48` 生成）。

### 2. 初始化数据库（一次性，复用已有 mysql 容器）

```bash
# ① 建库（已有库/云数据库分配的库则跳过；库名与 .env 的 MYSQL_DB 一致）
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 \
  -e "CREATE DATABASE IF NOT EXISTS stellar_ink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# ② 导入表结构（幂等）与种子数据（路径相对 deploy/docker/）
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/01_schema.sql
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/02_init-data.sql
```

若已有 mysql 容器的 root 不接受来自 Docker 网段的连接，先建专用账号（或用面板操作）：

```sql
CREATE USER IF NOT EXISTS 'stellar_ink'@'%' IDENTIFIED BY '强密码';
GRANT SELECT, INSERT, UPDATE, DELETE ON stellar_ink.* TO 'stellar_ink'@'%';
FLUSH PRIVILEGES;
```

并将 `.env` 的 `MYSQL_USER` 改为 `stellar_ink`。

### 3. 构建并启动

```bash
docker compose up -d --build
```

首次构建需拉取 Maven 依赖与 npm 包，约 10-20 分钟（BuildKit cache mount 缓存了 `~/.m2`，之后只做增量编译）。低配服务器建议分开执行：先 `docker compose build`，成功后再 `up -d`。

### 4. 验证

```bash
docker compose ps                             # 全部 Up (healthy)
curl http://127.0.0.1:8080/actuator/health    # 网关 {"status":"UP"}
curl -I http://127.0.0.1/                     # 前端 200
```

之后浏览器访问 `http://服务器IP/`；前端页面中 `/posts` 等接口由 Nginx 同源反代到网关，无跨域问题。

## 三、日常更新（发布新代码）

```bash
cd /path/to/stellar-ink && git pull
cd deploy/docker
docker compose up -d --build     # Maven/NPM 缓存加速，只重建变化部分
docker image prune -f            # 清理悬空旧镜像（可选）
```

只改前端：`docker compose up -d --build web`；只改某个后端服务：`docker compose up -d --build user-service`。

## 四、常用运维命令

```bash
docker compose ps                       # 状态与健康检查结果
docker compose logs -f gateway          # 跟踪单个服务日志
docker compose logs -f --tail=100       # 全部服务日志
docker compose restart user-service     # 重启单个服务
docker compose down                     # 停止并移除容器（nacos 数据在 volume 中，不丢）
docker compose down -v                  # ⚠️ 连 nacos 数据卷一并清空
```

- **Nacos 控制台**：8848 仅绑定宿主机回环。本机 `ssh -L 8848:127.0.0.1:8848 root@服务器` 后访问 `http://127.0.0.1:8848/nacos`。不要改成对外网开放。
- **业务日志**：容器内写 `/app/logs`，同时挂载到宿主机 `deploy/docker/logs/<服务名>/`（logback 按天 + 200MB 滚动，保留 30 天）。
- **Knife4j 接口文档**：各服务在内部网络，未对外暴露；调试需要时可临时在 compose 给对应服务加 `ports` 映射。
- **HTTPS**：建议由服务器上的宿主机 Nginx / Caddy / 云面板做 TLS 终结，反代到 `WEB_PORT` 与 `GATEWAY_PORT`，本编排不做证书管理。

## 五、内存预算（默认 mem_limit）

| 服务 | 上限 | | 服务 | 上限 |
|---|---|---|---|---|
| nacos | 640m | | 五个业务服务 | 各 384m |
| gateway | 512m | | stats | 320m |
| web | 64m | | **合计** | ≈ 3.1G |

JVM 堆按 `MaxRAMPercentage=70` 跟随容器上限。加上已有的 mysql/redis/qdrant，建议服务器 ≥ 4G 内存；
2C2G 机器请把业务服务降到 320m 并接受较紧的运行水位（直接改 compose 里的 `mem_limit`）。

## 六、常见问题

- **端口被占用**：改 `.env` 的 `WEB_PORT` / `GATEWAY_PORT`。
- **业务服务起不来、报 MySQL 连接失败**：核对 `.env` 密码与账号；确认该账号允许从 Docker 网段连接（见上文建账号 SQL）；`docker compose logs user-service` 看详情。
- **网关反复重启**：多为 `SA_TOKEN_JWT_SECRET` 未设置或各服务密钥不一致，检查 `.env`。
- **首次构建慢/超时**：国内网络可给 Docker daemon 配置镜像加速器；或本地 `docker compose build` 后 `docker save | docker load` 到服务器。
- **Sentinel dashboard 未部署**：网关 Sentinel 会尝试上报 `localhost:8858`，连接失败仅是无害告警，需要时再单独部署 dashboard。
- **Redis/Qdrant 接入**：业务暂未使用。将来接入时把对应服务加入 compose 网络即可（同一网络内直接用容器名作主机名，或继续走 host-gateway 用宿主机端口）。
