# 星笺 STELLAR INK Docker 部署手册

Docker Compose 一键编排：**网关 + 2 个业务服务 + 前端 Nginx**，复用宿主机已有 Nacos、MySQL 与 Redis。
所有 Java 服务走 `prod` profile，敏感配置统一放同目录 `.env`。

## 一、与服务器已有容器的关系

服务器上已独立运行（**本编排不创建、不接管**）：

| 容器 | 端口 | 与本部署的关系 |
|---|---|---|
| Nacos（宿主机进程） | 8848 / 9848 | **现在就用到**。Java 容器经 `host.docker.internal` 访问 |
| mysql | 3306 | **现在就用到**。业务服务经 `host.docker.internal`（host-gateway）访问宿主机 3306 |
| redis | 6379 | **现在就用到**。三个 Java 服务经 `host.docker.internal` 访问；承载登录防爆破、JWT 撤销、公开读模型及作者摘要缓存 |
| qdrant | 6333-6334 | 暂未使用（AI 能力预留） |

本编排启动的容器：

| 服务 | 容器端口 | 对外暴露 | 说明 |
|---|---|---|---|
| gateway | 8080 | **仅宿主机 `127.0.0.1`**（`GATEWAY_PORT`，默认 8080） | 后端唯一入口；前端经 web 容器走容器内网访问，不经宿主机端口 |
| user/content | 8101-8102 | 不暴露 | 网关经 Nacos 服务发现路由；content 内按领域分包；user-service 另挂载 `./data/uploads` 存头像 |
| web（nginx） | 80 / 443 | `WEB_PORT` / `WEB_HTTPS_PORT`（默认 80 / 443） | 前端静态资源 + API 前缀反代到网关；443 是 Cloudflare 回源入口（Origin 证书 + 回源校验），80 只做健康检查与 301 |

```
浏览器 ──▶ web(:80)──静态 SPA；/auth|/posts|/meteors|/echos|/links|/stats|/uploads… ──▶ gateway(:8080)
                                                                     gateway ──▶ 两个业务服务(8101-8102)
全部 Java 服务 ──▶ 宿主机 Nacos(:8848/9848) / Redis(:6379)    业务服务 ──▶ 宿主机 MySQL(:3306)
头像文件：浏览器 ──▶ web ──▶ gateway ──▶ user-service(:8101) ──▶ 卷 ./data/uploads
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
Redis 默认通过 `host.docker.internal:6379` 访问；若开启认证或使用其他实例，设置
`REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE`。Redis 不可达时三个 Java 服务的健康检查会变为 `DOWN`。

### 2. 初始化数据库（一次性，复用已有 mysql 容器）

```bash
# ① 建库（已有库/云数据库分配的库则跳过；库名与 .env 的 MYSQL_DB 一致）
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 \
  -e "CREATE DATABASE IF NOT EXISTS stellar_ink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# ② 导入表结构（幂等）与种子数据（路径相对 deploy/docker/）
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/01_schema.sql
docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/02_init-data.sql
```

已有数据库从旧版本升级时，再按顺序执行一次增量迁移（每个脚本只跑一次）：

```bash
for f in 03_multi-author 04_post_views_glow 05_user_role 06_note 07_role_apply 08_user_avatar; do
  docker exec -i mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/$f.sql
done
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
docker compose ps                             # 网关、2 个业务服务与 web 全部 Up (healthy)
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

只改前端：`docker compose up -d --build web`；只改业务后端：`docker compose up -d --build user-service` 或 `content-service`。

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
- **头像文件**：`STORAGE_TYPE=local`（默认）时容器内写 `/app/data/uploads`，挂载到宿主机 `deploy/docker/data/uploads/avatars/`。
  **该目录必须保留**（重建容器/换镜像不会带走它）；备份时连它一起打包，否则用户头像会 404 并自动降级为底字。
  `STORAGE_TYPE=cos` 时头像存腾讯云对象存储，该目录不再使用，可保留以备回滚。
- **头像改用 COS（推荐生产）**：在 `.env` 填 `STORAGE_TYPE=cos` + `COS_BUCKET` / `COS_REGION` /
  `COS_PUBLIC_BASE` / `COS_SECRET_ID` / `COS_SECRET_KEY`，然后
  `docker compose up -d user-service` 即可；**回滚**：把 `STORAGE_TYPE` 改回 `local` 重启。
  两种实现的 `avatar_url` 形态不同（相对路径 / 绝对 URL），互相切换时对方的地址会被安全忽略、不会误删文件。
  详见 `docs/architecture/avatar-minio.md`。
- **Knife4j 接口文档**：各服务在内部网络，未对外暴露；调试需要时可临时在 compose 给对应服务加 `ports` 映射。
- **HTTPS**：由 `web` 容器的 nginx 终结（Cloudflare Origin 证书 + SSL/TLS 模式 Full (strict) + Authenticated Origin Pulls），**详见第五节**；`:80` 只保留健康检查与到 https 的 301。
- **网关端口**：`gateway` 的 `8080` 只绑宿主机 `127.0.0.1`。前端经 `web` 容器走容器内网 `http://gateway:8080`，不经过宿主机端口；若把 8080 暴露到公网，`/actuator/prometheus` 等内部指标会匿名可达，且可绕过 nginx 的边缘限流直连网关。需本机调试时用 SSH 隧道：`ssh -L 8080:127.0.0.1:8080 root@服务器`。

## 五、HTTPS：Cloudflare + Origin 证书

站点由 Cloudflare 代理，**TLS 在 `web` 容器的 nginx 里终结**（`:443`），证书用 Cloudflare Origin 证书，
SSL/TLS 模式选 **Full (strict)**，并启用 **Authenticated Origin Pulls**（只有携带 Cloudflare 客户端证书的回源能握手）。

```text
浏览器 ──TLS(CF 边缘证书)──▶ Cloudflare ──TLS(Origin 证书 + 回源客户端证书)──▶ web:443 ──▶ gateway:8080
```

### 1. 放证书（不进仓库）

nginx 配置里把文件名写死为 `origin.pem` / `origin.key`，所以从 Cloudflare 下载后要改名：

```bash
cd deploy/docker
mkdir -p data/certs
mv ~/stellar.ink.pem data/certs/origin.pem      # Origin 证书
mv ~/stellar.ink.key data/certs/origin.key      # 私钥
chmod 600 data/certs/origin.key
```

- `data/` 已被 `.gitignore` 忽略，证书不会入库；compose 以只读方式挂到 `/etc/nginx/origin`。
- 回源 CA（`cloudflare-origin-pull-ca.pem`）**随镜像提供**，不需要你放；文件名见 `nginx/` 目录。

### 2. Cloudflare 面板设置（按这个顺序做）

| 顺序 | 位置 | 设置 |
|---|---|---|
| 1 | DNS | 站点域名加 A 记录指向源站 IP，代理状态**打开（橙云）** |
| 2 | SSL/TLS → Overview | 模式选 **Full (strict)** |
| 3 | SSL/TLS → Edge Certificates | 打开 **Always Use HTTPS** |
| 4 | SSL/TLS → Origin Server | 打开 **Authenticated Origin Pulls**（用「全局」那个，不是 Per-Hostname） |
| 5 | SSL/TLS → Edge Certificates | 需要时再开 HSTS（先 `max-age=15552000`，不要勾 preload） |
| 6 | Caching → Cache Rules | 建议加一条：`/auth`、`/user`、`/posts`、`/notes`、`/tags`、`/search`、`/meteors`、`/echos`、`/links`、`/stats` 前缀 **Bypass cache**，别把带鉴权语义的响应缓存到边缘 |

同时把 `.env` 的 `GATEWAY_CORS_ORIGINS` 改成实际域名（`https://你的域名`）并重启 gateway。

### 3. 上线

```bash
docker compose build web          # default.conf 是构建进镜像的，改了配置必须重建
docker compose up -d web
docker compose exec web nginx -t  # 先验证语法，再放流量
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/healthz   # 应得 200（走 :80）
```

用 `https://源站IP` 直连会被拒（握手缺客户端证书）——**这是预期行为**，验证请走自己的域名（经 Cloudflare）。

### 4. 三个容易踩的坑

1. **顺序**：Authenticated Origin Pulls 必须**先在 Cloudflare 打开**，再上线带 `ssl_verify_client on` 的配置；
   反过来会让所有请求在 TLS 握手阶段被拒（nginx 返回 400）。
2. **真实 IP 必须还原**：Cloudflare 回源后 `$remote_addr` 会变成 CF 边缘 IP，而本编排的边缘限流正是按它做的
   —— 所以 `nginx/cloudflare_real_ip.inc` 里列了 CF 全部网段并指定 `CF-Connecting-IP`。
   **该文件要定期对齐**（每季度对一次 <https://www.cloudflare.com/ips-v4>）：漏了网段的表现为部分访客被当成
   CF 的 IP，限流串号、日志里看不到真实来源。
3. **Cloudflare 不保护非 HTTP 端口**：3306 / 6379 / 8848 该关还得关（安全组只放行自己的出口 IP，或走 SSH 隧道）。
   站点放到 CF 后面不等于源站安全，源站真实 IP 也可能通过 DNS 历史或证书透明度日志被找到。

> 以后接 SSE / AI 流式时注意：Cloudflare 免费版对代理请求有约 100 秒的无数据超时（超时返回 524），
> 长连接需要心跳；nginx 侧还要对相应 location 关掉 `proxy_buffering`。

## 六、安全配置要点（上线前请确认）

| 项 | 现状 | 说明 |
|---|---|---|
| 网关自动路由 | **已关闭** | `spring.cloud.gateway.discovery.locator.enabled: false`。开启后会生成 `/{serviceId}/**` 自动路由（如 `/content-service/**`），该前缀不在鉴权白名单内，**可绕过网关鉴权直接读写下游**（含 `/internal/**`）。路由一律在 `routes` 中显式声明 |
| 网关鉴权策略 | **默认拒绝 + 显式白名单** | 除登录、读请求、公开写接口外一律要求有效 token。新增路由默认受保护，不会因漏配置而裸奔 |
| Actuator | 已收敛 | 仅 `health,info,metrics,loggers`；`heapdump`/`env`/`configprops`/`beans`/`threaddump`/`shutdown` 已单独 `enabled: false`。**heapdump 可导出堆内存明文（含 JWT 密钥），脱敏无效，绝不可暴露** |
| 边缘限流 | 已启用 | Nginx `limit_req`：`/auth/login` 10 次/分、`/echos`+`/links`+glow 6 次/分、其余 API 50 次/秒。`$binary_remote_addr` 取自 TCP 连接不可伪造，比应用层按 `X-Forwarded-For` 限流可靠；**在 Cloudflare 后面时必须靠 `cloudflare_real_ip.inc` 还原真实 IP**，否则全站访客共用一个 CF 边缘 IP，会互相挤掉限额（见第五节第 4 条） |
| 头像上传 | 已加固 | 服务端重命名（不用客户端文件名，杜绝 `../` 与可执行后缀）、ImageIO 读魔数认格式、1MB 双拦（multipart + 业务层），Nginx `client_max_body_size 2m` 兜底；`/uploads/` 只读且由 nginx 单独转发到网关（不放静态目录，避免被长缓存规则截走） |
| 登录防爆破 | 已启用 | user-service 按规范化用户名在 Redis 中维护 15 分钟失败窗口，连续失败 5 次锁定 15 分钟，多实例共享状态 |
| JWT 撤销 | 已启用 | 登出或改密后，当前 JWT 摘要进入 Redis 直到自然过期；网关响应式检查，Redis 故障时返回 503 而不是放行撤销令牌 |
| Redis 业务缓存 | 已启用 | 公开文章/笔记及聚合读模型采用 30 秒至 5 分钟 TTL，写后按命名空间失效；完整用户资料、JWT 原文与持久业务明细不进 Redis |
| 容器权限 | 已加固 | 3 个 Java 服务均 `cap_drop: ALL` + `no-new-privileges:true`。容器仍以 root 运行：日志目录是 bind mount，会覆盖镜像内的属主设置，改非 root 需同步调整 `deploy/docker/logs/` 的属主 |
| 网关端口 | 仅宿主机回环 | `127.0.0.1:${GATEWAY_PORT}:8080`。前端走容器内网 `gateway:8080`，无需对外发布端口 |
| JWT 密钥 | 启动即校验 | prod 下若密钥为空、少于 32 字符，或等于仓库中 dev 默认值，**直接拒绝启动**（common-core `SecretGuard`）。本编排对 3 个 Java 服务统一注入 `SA_TOKEN_JWT_SECRET`，密钥弱时整体拒绝启动（fail-closed） |

## 七、内存预算（默认 mem_limit）

| 服务 | 上限 | | 服务 | 上限 |
|---|---|---|---|---|
| gateway | 256m | | user-service | 256m |
| content-service | 256m | | web | 64m |
| **本编排合计** | ≈ 832m | | 宿主机 Nacos | 独立预算 |

Java 服务统一使用 `-Xms32m -Xmx128m` 和 `SerialGC`，适合低并发个人博客；`256m` 上限包含 JVM 堆外内存，不建议继续盲目下调。
加上已有的 mysql/redis/qdrant，建议服务器至少 2G 内存；如果出现容器 `OOMKilled` 或 `OutOfMemoryError`，再把相关服务上限调到 320m。

## 八、常见问题

- **端口被占用**：改 `.env` 的 `WEB_PORT` / `GATEWAY_PORT`。
- **业务服务起不来、报 MySQL 连接失败**：核对 `.env` 密码与账号；确认该账号允许从 Docker 网段连接（见上文建账号 SQL）；`docker compose logs user-service` 看详情。
- **业务服务健康检查为 DOWN、Redis 连接失败**：核对 `.env` 的 `REDIS_*`；确认 Redis 已监听宿主机 6379，且允许 Docker 网桥访问。不要把容器内的 `127.0.0.1` 当作宿主机。
- **网关反复重启**：多为 `SA_TOKEN_JWT_SECRET` 未设置或各服务密钥不一致，检查 `.env`。
- **业务服务启动报 `SecretGuard ... 拒绝启动`**：说明 prod 下 JWT 密钥为空/过短/沿用了仓库中的 dev 默认值。
  用 `openssl rand -base64 48` 重新生成并写入 `.env` 的 `SA_TOKEN_JWT_SECRET` 即可。
- **Java 服务无法注册 Nacos**：确认宿主机 Nacos 的 8848/9848 对 Docker 网桥可达，并核对 `.env` 中的账号密码。
- **前端报 429**：触发了 Nginx 限流（`limit_req`）。阈值见 `deploy/docker/nginx/default.conf`，按需调整。
- **首次构建慢/超时**：国内网络可给 Docker daemon 配置镜像加速器；或本地 `docker compose build` 后 `docker save | docker load` 到服务器。
- **Sentinel dashboard 未部署**：网关 Sentinel 会尝试上报 `localhost:8858`，连接失败仅是无害告警，需要时再单独部署 dashboard。
- **Qdrant 接入**：业务暂未使用。将来接入时可加入 compose 网络，或继续走 host-gateway 使用宿主机端口。
