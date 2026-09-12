# 星笺 STELLAR INK Docker 部署手册

Docker Compose 一键编排：**网关 + 6 个微服务 + 前端 Nginx**，复用宿主机已有 Nacos。
所有 Java 服务走 `prod` profile，敏感配置统一放同目录 `.env`。

## 一、与服务器已有容器的关系

服务器上已独立运行（**本编排不创建、不接管**）：

| 容器 | 端口 | 与本部署的关系 |
|---|---|---|
| Nacos（宿主机进程） | 8848 / 9848 | **现在就用到**。Java 容器经 `host.docker.internal` 访问 |
| mysql | 3306 | **现在就用到**。业务服务经 `host.docker.internal`（host-gateway）访问宿主机 3306 |
| redis | 6379 | 暂未使用（将来接入限流/会话时再纳入 compose 网络） |
| qdrant | 6333-6334 | 暂未使用（AI 能力预留） |

本编排启动的容器：

| 服务 | 容器端口 | 对外暴露 | 说明 |
|---|---|---|---|
| gateway | 8080 | **仅宿主机 `127.0.0.1`**（`GATEWAY_PORT`，默认 8080） | 后端唯一入口；前端经 web 容器走容器内网访问，不经宿主机端口 |
| user/post/meteor/echo/link/stats | 8101-8106 | 不暴露 | 内部网络经 Nacos 服务发现互相调用 |
| web（nginx） | 80 | `WEB_PORT`（默认 80） | 前端静态资源 + `/posts` 等 API 前缀反代到网关 |

```
浏览器 ──▶ web(:80)──静态 SPA；/auth|/posts|/meteors|/echos|/links|/stats… ──▶ gateway(:8080)
                                                                     gateway ──▶ 各业务服务(8101-8106)
全部 Java 服务 ──▶ 宿主机 Nacos(:8848/9848)    业务服务 ──▶ 宿主机 3306（已有 mysql 容器）
```

## 二、首次部署

> 要求：宿主机 Nacos 已启动且 8848/9848 对 Docker 网桥可达；Docker 20.10+（`host-gateway` 支持）与 Docker Compose v2；服务器内存建议 ≥ 4G。

### 1. 配置环境变量（必做）

```bash
cd deploy/docker
cp .env.example .env
vi .env
```

必填三项，**留空会导致 `docker compose` 直接报错退出**（刻意设计的 fail-safe，宁可起不来也不要默认放开）：

| 变量 | 说明 |
|---|---|
| `MYSQL_PASSWORD` | 数据库口令 |
| `SA_TOKEN_JWT_SECRET` | JWT 签名密钥，`openssl rand -base64 48` 生成 |
| `GATEWAY_CORS_ORIGINS` | 前端实际域名，多个逗号分隔；**不要填 `*`** |
`NACOS_ADDR` 默认是 `host.docker.internal:8848`；`NACOS_USERNAME` / `NACOS_PASSWORD` 必须与宿主机现有 Nacos 一致。

### 2. 初始化数据库（一次性，复用已有 mysql 容器）

```bash
# ① 建库（已有库/云数据库分配的库则跳过；库名与 .env 的 MYSQL_DB 一致）
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 \
  -e "CREATE DATABASE IF NOT EXISTS stellar_ink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# ② 导入表结构（幂等）与种子数据（路径相对 deploy/docker/）
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/01_schema.sql
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/02_init-data.sql
```

已有数据库从旧版本升级时，再执行一次多作者归属迁移：

```bash
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/03_multi-author.sql
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
docker compose ps                             # 网关、6 个业务服务与 web 全部 Up (healthy)
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
docker compose down                     # 停止并移除本项目容器，不影响宿主机 Nacos/MySQL
```

- **Nacos 控制台**：由宿主机 `/opt/nacos/` 独立维护；不要将 8848/9848 暴露到公网。
- **业务日志**：容器内写 `/app/logs`，同时挂载到宿主机 `deploy/docker/logs/<服务名>/`（logback 按天 + 200MB 滚动，保留 30 天）。
- **Knife4j 接口文档**：各服务在内部网络，未对外暴露；调试需要时可临时在 compose 给对应服务加 `ports` 映射。
- **HTTPS**：建议由服务器上的宿主机 Nginx / Caddy / 云面板做 TLS 终结，反代到 `WEB_PORT`；如需直连网关，反代到 `127.0.0.1:GATEWAY_PORT`（网关端口只绑回环，见下条），本编排不做证书管理。
- **网关端口**：`gateway` 的 `8080` 只绑宿主机 `127.0.0.1`。前端经 `web` 容器走容器内网 `http://gateway:8080`，不经过宿主机端口；若把 8080 暴露到公网，`/actuator/prometheus` 等内部指标会匿名可达，且可绕过 nginx 的边缘限流直连网关。需本机调试时用 SSH 隧道：`ssh -L 8080:127.0.0.1:8080 root@服务器`。

## 五、安全配置要点（上线前请确认）

| 项 | 现状 | 说明 |
|---|---|---|
| 网关自动路由 | **已关闭** | `spring.cloud.gateway.discovery.locator.enabled: false`。开启后会生成 `/{serviceId}/**` 自动路由（如 `/post-service/**`），该前缀不在鉴权白名单内，**可绕过网关鉴权直接读写下游**（含 `/internal/**`）。路由一律在 `routes` 中显式声明 |
| 网关鉴权策略 | **默认拒绝 + 显式白名单** | 除登录、读请求、公开写接口外一律要求有效 token。新增路由默认受保护，不会因漏配置而裸奔 |
| Actuator | 已收敛 | 仅 `health,info,metrics,loggers`；`heapdump`/`env`/`configprops`/`beans`/`threaddump`/`shutdown` 已单独 `enabled: false`。**heapdump 可导出堆内存明文（含 JWT 密钥），脱敏无效，绝不可暴露** |
| 边缘限流 | 已启用 | Nginx `limit_req`：`/auth/login` 10 次/分、`/echos`+`/links`+glow 6 次/分、其余 API 50 次/秒。`$binary_remote_addr` 取自 TCP 连接不可伪造，比应用层按 `X-Forwarded-For` 限流可靠 |
| 登录防爆破 | 已启用 | user-service 按用户名计数，连续失败 5 次锁定 15 分钟（进程内实现，**多实例部署需换 Redis**） |
| 容器权限 | 已加固 | 7 个 Java 服务均 `cap_drop: ALL` + `no-new-privileges:true`。容器仍以 root 运行：日志目录是 bind mount，会覆盖镜像内的属主设置，改非 root 需同步调整 `deploy/docker/logs/` 的属主 |
| 网关端口 | 仅宿主机回环 | `127.0.0.1:${GATEWAY_PORT}:8080`。前端走容器内网 `gateway:8080`，无需对外发布端口 |
| JWT 密钥 | 启动即校验 | prod 下若密钥为空、少于 32 字符，或等于仓库中 dev 默认值，**直接拒绝启动**（common-core `SecretGuard`）。校验范围＝配置了 `sa-token.jwt-secret-key` 的服务；本编排对 7 个服务统一注入 `SA_TOKEN_JWT_SECRET`，因此 7 个都会校验（密钥弱则整体拒绝启动，属预期的 fail-closed） |

## 六、内存预算（默认 mem_limit）

| 服务 | 上限 | | 服务 | 上限 |
|---|---|---|---|---|
| gateway | 256m | | 五个业务服务 | 各 256m |
| stats | 256m | | web | 64m |
| **本编排合计** | ≈ 1.86G | | 宿主机 Nacos | 独立预算 |

Java 服务统一使用 `-Xms32m -Xmx128m` 和 `SerialGC`，适合低并发个人博客；`256m` 上限包含 JVM 堆外内存，不建议继续盲目下调。
加上已有的 mysql/redis/qdrant，建议服务器至少 2G 内存；如果出现容器 `OOMKilled` 或 `OutOfMemoryError`，再把相关服务上限调到 320m。

## 七、常见问题

- **端口被占用**：改 `.env` 的 `WEB_PORT` / `GATEWAY_PORT`。
- **业务服务起不来、报 MySQL 连接失败**：核对 `.env` 密码与账号；确认该账号允许从 Docker 网段连接（见上文建账号 SQL）；`docker compose logs user-service` 看详情。
- **网关反复重启**：多为 `SA_TOKEN_JWT_SECRET` 未设置或各服务密钥不一致，检查 `.env`。
- **业务服务启动报 `SecretGuard ... 拒绝启动`**：说明 prod 下 JWT 密钥为空/过短/沿用了仓库中的 dev 默认值。
  用 `openssl rand -base64 48` 重新生成并写入 `.env` 的 `SA_TOKEN_JWT_SECRET` 即可。
- **Java 服务无法注册 Nacos**：确认宿主机 Nacos 的 8848/9848 对 Docker 网桥可达，并核对 `.env` 中的账号密码。
- **前端报 429**：触发了 Nginx 限流（`limit_req`）。阈值见 `deploy/docker/nginx/default.conf`，按需调整。
- **首次构建慢/超时**：国内网络可给 Docker daemon 配置镜像加速器；或本地 `docker compose build` 后 `docker save | docker load` 到服务器。
- **Sentinel dashboard 未部署**：网关 Sentinel 会尝试上报 `localhost:8858`，连接失败仅是无害告警，需要时再单独部署 dashboard。
- **Redis/Qdrant 接入**：业务暂未使用。将来接入时把对应服务加入 compose 网络即可（同一网络内直接用容器名作主机名，或继续走 host-gateway 用宿主机端口）。
