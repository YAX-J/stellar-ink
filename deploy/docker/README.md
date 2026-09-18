# 星笺 STELLAR INK Docker 部署手册

Docker Compose 一键编排：**Nacos + MySQL + Redis + Qdrant + 网关 + 2 个业务服务 + 前端 Nginx**，
一台机器整栈自洽（不再依赖宿主机预装任何中间件）。所有 Java 服务走 `prod` profile，敏感配置统一放同目录 `.env`。

## 一、拓扑与三个环境

本编排启动的服务：

| 服务 | 容器端口 | 对外暴露 | 说明 |
|---|---|---|---|
| web（nginx） | 80 / 443 | `WEB_PORT` / `WEB_HTTPS_PORT`（默认 80 / 443） | **唯一对公网入口**：静态 SPA + API 反代到网关；443 是 Cloudflare 回源入口 |
| gateway | 8080 | 仅宿主机 `127.0.0.1`（`GATEWAY_PORT`） | 后端唯一入口；前端经 web 容器走容器内网访问，不经宿主机端口 |
| user / content | 8101-8102 | 不暴露 | 网关经 Nacos 服务发现路由；user-service 另挂载 `./data/uploads` 存头像 |
| nacos | 8848 / 9848 | 仅宿主机 `127.0.0.1:8848`（控制台） | 单机模式 + 内置 Derby；容器间走服务名 `nacos:8848` |
| mysql | 3306 | 仅宿主机 `127.0.0.1`（`MYSQL_BIND_PORT`） | 首次启动自动导入 `deploy/sql/01_schema.sql` + `02_init-data.sql` |
| redis | 6379 | 仅宿主机 `127.0.0.1`（`REDIS_BIND_PORT`） | 开启 AOF：JWT 撤销列表与登录失败计数需跨重启保留 |
| qdrant | 6333 | 仅宿主机 `127.0.0.1`（`QDRANT_BIND_PORT`） | 业务暂未使用，AI 阶段（M3 起）接入 |

```
浏览器 ──TLS(CF 边缘证书)──▶ Cloudflare ──TLS(Origin 证书)──▶ web:443
web:80 ──健康检查与 301；web ──/auth|/posts|/notes|…──▶ gateway:8080 ──▶ user/content(8101-8102)
全部 Java 服务 ──▶ nacos:8848（注册/配置） / redis:6379       user/content ──▶ mysql:3306
头像文件：浏览器 ──▶ web ──▶ gateway ──▶ user-service ──▶ 卷 ./data/uploads
```

**三个环境**（只有域名、密钥与规模不同，配置结构完全一致）：

| 环境 | 位置 | 用途 | 注意 |
|---|---|---|---|
| **生产** | 香港 / 海外（境外源站无需 ICP 备案） | 对外站点 | 走 Cloudflare `Full (strict)`，源站安全组只放行 Cloudflare 网段 |
| **测试** | 大陆机器（腾讯云） | 开发联调、备份落地 | ⚠️ **绝不要把生产域名解析到大陆机器**：未备案域名 + 入站 443 带 SNI 会被网络层重置，表现为 Cloudflare 525（详见第五节第 5 条）。测试用 IP 访问，或在本机 hosts 里写一个公网不解析的假域名 |
| **本地开发** | 开发机 | `npm run dev` + 网关 :8080 | 见仓库根 `AGENTS.md` |

两个环境**必须使用不同的 `SA_TOKEN_JWT_SECRET` 与数据库口令**——Sa-Token 只验签、不看环境，共用密钥等于两边令牌互通。

## 二、首次部署

> 要求：Docker 20.10+ 与 Docker Compose v2；建议 ≥ 4G 内存（8G 更稳，本编排容器内存上限合计约 3.5G）。
> 不再需要宿主机预装 Nacos / MySQL / Redis —— 它们都在本编排内。

### 1. 配置环境变量（必做）

```bash
cd deploy/docker
cp .env.example .env
vi .env
```

必填四项，**留空会导致 `docker compose` 直接报错退出**（刻意设计的 fail-safe：宁可起不来，也不要默认放开）：

| 变量 | 说明 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 口令（备份脚本与手工导入时使用） |
| `MYSQL_PASSWORD` | 业务专用账号（`MYSQL_USER`，默认 `stellar`）的口令 |
| `SA_TOKEN_JWT_SECRET` | JWT 签名密钥，`openssl rand -base64 48` 生成 |
| `GATEWAY_CORS_ORIGINS` | 前端实际域名，多个用逗号分隔；**不要填 `*`** |

其余变量都有可用默认值。容器之间用服务名互访（`mysql` / `redis` / `nacos`）；
只有把中间件换回宿主机进程时，才需要改 `MYSQL_HOST` / `REDIS_HOST` / `NACOS_ADDR` 为 `host.docker.internal`。

> ⚠️ 别忘了把 Cloudflare Origin 证书放到 `data/certs/origin.pem` 与 `origin.key`（第五节第 1 条），否则 web 容器起不来。

### 2. 数据库初始化：首次启动自动完成

`mysql` 容器**首次启动**（数据卷为空）时会自动按文件名顺序执行挂载到 `/docker-entrypoint-initdb.d` 的：

- `01_schema.sql` —— 建表（当前完整结构）
- `02_init-data.sql` —— 种子内容（文章/笔记/流星/回声/友链/评论/用户）

库名取自 `MYSQL_DB`，业务账号由容器按 `MYSQL_USER` / `MYSQL_PASSWORD` 自动创建并授权。

> ⚠️ **中文变乱码的坑（已修，但你若在此之前初始化过就要重建）**：Docker 的 mysql 镜像自动执行
> `/docker-entrypoint-initdb.d/*.sql` 时**不带** `--default-character-set`，而容器内 locale 是 POSIX/C，
> mysql 客户端默认的 `auto` 就退回 `latin1` —— UTF-8 的中文被当 latin1 读入再转存，网页上显示成
> 「æ×–â®¹」这类乱码（前端自己的文案正常，只有接口数据乱）。现在 `01_schema.sql` 与
> `02_init-data.sql` 开头都加了 `SET NAMES utf8mb4;` 兜底。
> **如果数据是在修复前导入的，必须删掉数据卷让它重跑**（此时库里的乱码无法靠改配置恢复）：
>
> ```bash
> docker compose down
> docker volume rm stellar-ink_mysql-data     # 卷名 = <项目名>_mysql-data
> git pull                                     # 拿到带 SET NAMES 的 SQL
> docker compose up -d
> # 验证中文正常（能正确显示「拾星人」这类中文才算对）：
> docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
>   stellar_ink -e "SELECT id, LEFT(title,20) AS title FROM post ORDER BY id DESC LIMIT 3;"
> ```

**只挂这两个文件是刻意的**：`01_schema.sql` 已包含全部历史变更（`post_glow`、`post_view`、`post_comment`、
`note`、`user.avatar_url`、`user.role_applied_at` 都在里面），而 `03`–`09` 是给**早期已有库**做增量升级的，
在全新库上重复执行会报「列已存在」。

**从旧库迁移**（例如把测试机的数据搬过来）时不要重新初始化，改为导入 dump：

```bash
gunzip -c /path/to/mysql.sql.gz | docker compose exec -T mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$MYSQL_DB"
```

**确实要在已有旧库上升级**时，按顺序各执行一次（每个脚本只跑一次）：

```bash
for f in 03_multi-author 04_post_views_glow 05_user_role 06_note 07_role_apply 08_user_avatar 09_comment; do
  docker compose exec -T mysql mysql -uroot -p'密码' --default-character-set=utf8mb4 stellar_ink < ../sql/$f.sql
done
```

### 3. 构建并启动

```bash
docker compose build            # 首次需拉基础镜像 + Maven/npm 依赖，约 10–20 分钟
docker compose up -d
```

低配机器建议分开执行（先 `build` 成功，再 `up -d`）。BuildKit 的 cache mount 会缓存 `~/.m2`，之后只做增量编译。

> **拉不动基础镜像**（`node:22-alpine`、`nginx:1.27-alpine`、`mysql:8.0`、`redis:7-alpine`、`nacos`、`qdrant`）时，
> 给 Docker 配镜像加速器：在 `/etc/docker/daemon.json` 里加 `"registry-mirrors": ["https://<你的加速器>"]`，
> 再 `sudo systemctl daemon-reload && sudo systemctl restart docker`。境外机器也可直接问云厂商要官方加速地址。

### 4. 验证

```bash
docker compose ps                             # nacos/mysql/redis/qdrant/gateway/user/content/web 全部 Up
curl http://127.0.0.1:8080/actuator/health    # 网关 {"status":"UP"}
curl -I http://127.0.0.1/                     # 前端 200
docker stats --no-stream                      # 看实际内存占用，与第七节预算对照
```

`mysql` / `redis` 带健康检查，业务服务用 `depends_on: condition: service_healthy` 等它们就绪；
`nacos` 没有健康检查（镜像内没有可靠的探针命令），服务注册由 nacos-client 自动重试，启动后多等十几秒即可。

之后浏览器访问 `https://你的域名/`（经 Cloudflare）。

## 三、日常更新（发布新代码）

```bash
cd /path/to/stellar-ink && git pull
cd deploy/docker
docker compose up -d --build     # Maven/NPM 缓存加速，只重建变化部分
docker image prune -f            # 清理悬空旧镜像（可选）
```

只改前端：`docker compose up -d --build web`；只改后端某个服务：`docker compose up -d --build content-service`。

## 四、常用运维命令

```bash
docker compose ps                        # 状态与健康检查结果
docker compose logs -f gateway           # 跟踪单个服务日志
docker compose logs -f --tail=100        # 全部服务日志
docker compose restart user-service      # 重启单个服务
docker compose down                      # 停止并移除全部容器（具名卷保留，数据不丢）
docker compose exec mysql mysql -uroot -p'密码' stellar_ink   # 进 MySQL
docker compose exec redis redis-cli                           # 进 Redis
```

- **Nacos 控制台**：只绑宿主机回环，用 SSH 隧道打开：`ssh -L 8848:127.0.0.1:8848 <服务器>`，然后浏览器访问
  `http://127.0.0.1:8848/nacos`。**不要把 8848/9848 暴露到公网**（Nacos 未授权访问是常见入侵入口）。
- **业务日志**：容器内写 `/app/logs`，同时挂到宿主机 `deploy/docker/logs/<服务名>/`
  （logback 按天 + 单文件 100MB 滚动，保留 7 天，总上限 2G）。
- **头像文件**：`STORAGE_TYPE=local`（默认）时写 `/app/data/uploads`，挂到宿主机 `deploy/docker/data/uploads/`。
  **该目录必须保留**（重建容器/换镜像不会带走它）；备份时连它一起打包，否则头像 404 并降级为底字。
  `STORAGE_TYPE=cos` 时头像存腾讯云对象存储，该目录不再使用，可保留以备回滚。
- **头像改用 COS（推荐生产）**：`.env` 填 `STORAGE_TYPE=cos` + `COS_BUCKET` / `COS_REGION` /
  `COS_PUBLIC_BASE` / `COS_SECRET_ID` / `COS_SECRET_KEY`，然后 `docker compose up -d user-service`；
  **回滚**：`STORAGE_TYPE` 改回 `local` 重启。两种实现的 `avatar_url` 形态不同（相对路径 / 绝对 URL），
  互相切换时对方的地址会被安全忽略、不会误删文件。详见 `docs/architecture/avatar-minio.md`。
- **Knife4j 接口文档**：各服务只在编排网络内，未对外暴露；调试需要时临时给对应服务加 `ports` 映射。
- **HTTPS**：由 `web` 容器的 nginx 终结（Cloudflare Origin 证书 + `Full (strict)`），**详见第五节**。
- **网关端口**：`gateway` 的 8080 只绑宿主机 `127.0.0.1`。前端经 `web` 走容器内网 `http://gateway:8080`；
  若把 8080 暴露到公网，`/actuator/prometheus` 等内部指标会匿名可达，且可绕过 nginx 的边缘限流。
  需本机调试时用 SSH 隧道：`ssh -L 8080:127.0.0.1:8080 <服务器>`。

## 五、HTTPS 与 Cloudflare

TLS 在 `web` 容器的 nginx 里终结（`:443`），证书用 Cloudflare Origin 证书，SSL/TLS 模式选 **Full (strict)**。

```text
浏览器 ──TLS(CF 边缘证书)──▶ Cloudflare ──TLS(Origin 证书)──▶ web:443 ──▶ gateway:8080
```

### 1. 放证书（不进仓库）

nginx 配置把文件名写死为 `origin.pem` / `origin.key`，从 Cloudflare 下载后改名：

```bash
cd deploy/docker
mkdir -p data/certs
mv ~/你的域名.pem data/certs/origin.pem      # Origin 证书
mv ~/你的域名.key data/certs/origin.key      # 私钥
chmod 600 data/certs/origin.key
docker compose up -d web                      # 或首次 build 后直接 up
```

`data/` 已被 `.gitignore` 忽略，证书不会入库；compose 以只读方式挂到 `/etc/nginx/origin`。

### 2. Cloudflare 面板设置（按顺序）

| 顺序 | 位置 | 设置 |
|---|---|---|
| 1 | DNS | 域名加 A 记录指向源站 IP，代理状态**打开（橙云）** |
| 2 | SSL/TLS → Overview | 模式选 **Full (strict)** |
| 3 | SSL/TLS → Edge Certificates | 打开 **Always Use HTTPS**；需要时再开 HSTS（先 `max-age=15552000`，不勾 preload） |
| 4 | Caching → Cache Rules | ① `URI Path equals /` + `Method equals GET` → Eligible，Edge/Browser TTL 60s，开 Cache Deception Armor（首页 HTML 短缓存，显著降 TTFB）；② 对 `/auth`、`/user`、`/posts`、`/notes`、`/tags`、`/search`、`/meteors`、`/echos`、`/links`、`/stats` **前缀 Bypass cache**（别把带鉴权语义的响应缓存到边缘） |

同时把 `.env` 的 `GATEWAY_CORS_ORIGINS` 改成实际域名（`https://你的域名`）并重启 gateway。

### 3. 源站保护：安全组白名单 + 主机防火墙（不用 mTLS）

nginx **没有**启用 Authenticated Origin Pulls（`ssl_verify_client`），保护交给网络层，共两道。

**第一道：云安全组**（拦得住 Docker，最重要）

| 方向 | 协议 | 端口 | 来源 | 说明 |
|---|---|---|---|---|
| 入站 | TCP | 22 | 你的固定 IP `/32` | 唯一管理入口；本地连库走的 SSH 隧道也用它 |
| 入站 | TCP | 443 | Cloudflare 全部网段（15×IPv4 + 7×IPv6，见 `nginx/cloudflare_real_ip.inc`） | CF 回源 |
| 入站 | TCP | 80 | 同上（也可以完全不开） | 只有 CF 走 80 时才需要；容器健康检查走内部回环，不依赖它 |
| 入站 | ICMP | — | `0.0.0.0/0` | 保留路径 MTU 发现，避免大包被丢导致 TLS 握手偶发失败 |
| 入站 | 其他 | 全部 | — | **一律拒绝**（3306/6379/6333/8848 没发布端口，这里再兜一层） |
| 出站 | ALL | ALL | `0.0.0.0/0` | 拉镜像、调云 API、依赖下载 |

**第二道（可选）：主机防火墙 `deploy/scripts/firewall.sh`**

⚠️ 坑在这里：**ufw 拦不住 Docker 发布的端口**——Docker 会往 `FORWARD` 链插自己的 ACCEPT 规则，
绕过 ufw 的 `INPUT` 规则。要真正限制容器端口，必须往 `DOCKER-USER` 链写规则，脚本已经做了：

```bash
sudo bash deploy/scripts/firewall.sh --dry-run            # 先看会加哪些规则，不改动
sudo bash deploy/scripts/firewall.sh                      # 应用（SSH 来源自动识别）
sudo bash deploy/scripts/firewall.sh --install-systemd    # 应用并装成开机自启（Docker 重启会重建 DOCKER-USER）
```

- Cloudflare 网段从 `nginx/cloudflare_real_ip.inc` 读取，**单一来源**，不用两处维护；
- 只放行 22（管理 IP）、80/443（CF 网段）与 ICMP，其余入站拒绝；
  `127.0.0.1` 发布的服务不受影响，SSH 隧道照常用；
- 🔴 **锁死自救**：脚本会先放行当前 SSH 来源，但从本地控制台运行时务必带 `--ssh-source <你的IP>`；
  真把自己关在门外了，用云控制台的 VNC（网页终端）登录执行 `ufw disable`。

为什么不用 mTLS：生产与测试共用同一份 nginx 配置，测试机要能直接用 IP 访问（mTLS 会让直连在握手后被判 400）；
上面两层效果相同，却不依赖 Cloudflare 面板开关，也少一个「先开开关再上线」的顺序坑。

### 4. 上线与验证

```bash
docker compose build web          # default.conf 是构建进镜像的，改了配置必须重建
docker compose up -d web
docker compose exec web nginx -t  # 先验证语法，再放流量
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/healthz   # 走 :80，应得 200
curl -sI https://你的域名/ | head -3                                  # 经 Cloudflare，应得 200
```

### 5. ⚠️ 大陆源站必读：Cloudflare 525 与「未备案拦截」

**症状**：站点经 Cloudflare 返回 **525 SSL handshake failed**，而源站看起来完全正常
（直连 IP 能拿到 200、证书有效、nginx 没有任何证书或配置报错、日志里只有
`peer closed connection in SSL handshake (104: Connection reset by peer)`）。

**根因**：**源站网络在按 SNI 拦截未备案域名**——握手在到达 nginx 之前就被重置。
判断方法（在一台外部机器上跑，`<源站IP>` 换成你的地址）：

```bash
# A) 带真实域名 SNI：大陆源站上会被重置（curl 返回 000 / 握手失败）
curl -skI --max-time 8 --resolve 你的域名:443:<源站IP> https://你的域名/ -o /dev/null -w 'SNI+域名: %{http_code}\n'
# B) 不带 SNI：应当正常返回 200
curl -skI --max-time 8 https://<源站IP>/ -H 'Host: 你的域名' -o /dev/null -w '无SNI    : %{http_code}\n'
# C) 对照组：带一个已备案域名的 SNI，应当正常
curl -skI --max-time 8 --resolve www.baidu.com:443:<源站IP> https://www.baidu.com/ -o /dev/null -w '已备案SNI: %{http_code}\n'
```

**A 失败 + B 和 C 成功**即为「未备案拦截」：只有「SNI 能解析出来、且不在备案库里」的握手被重置，
不带 SNI 或解析不出来的域名反而放行。Cloudflare 回源**永远带 SNI**，所以必然中招。

**结论**：
- 源站在**大陆** → 域名必须完成 ICP 备案；否则只能把源站放到**境外**（境外源站无备案要求，也就是本手册的推荐拓扑）；
- 这与 Cloudflare、证书、nginx 配置**都无关**，换端口也没用（腾讯云对非 80/443 端口同样拦截）；
- 排查时若看到 525，先跑上面三条命令，不要围着 nginx 和证书转。

> 补充：Cloudflare 免费版把大陆访客调度到境外 POP（常见 SJC/SEA/HKG），源站放境外反而离回源节点更近。
> 浏览器看到的永远是 Cloudflare 边缘证书，源站位置不影响访客侧的 TLS。

## 六、安全配置要点（上线前请确认）

| 项 | 现状 | 说明 |
|---|---|---|
| 网关自动路由 | **已关闭** | `spring.cloud.gateway.discovery.locator.enabled: false`。开启后会生成 `/{serviceId}/**` 自动路由（如 `/content-service/**`），该前缀不在鉴权白名单内，**可绕过网关鉴权直接读写下游**（含 `/internal/**`）。路由一律在 `routes` 中显式声明 |
| 网关鉴权策略 | **默认拒绝 + 显式白名单** | 除登录、读请求、公开写接口外一律要求有效 token。新增路由默认受保护，不会因漏配置而裸奔 |
| Actuator | 已收敛 | 仅 `health,info,metrics,loggers`；`heapdump`/`env`/`configprops`/`beans`/`threaddump`/`shutdown` 已单独 `enabled: false`。**heapdump 可导出堆内存明文（含 JWT 密钥），脱敏无效，绝不可暴露** |
| 边缘限流 | 已启用 | Nginx `limit_req`：`/auth/login` 10 次/分、`/echos`+`/links`+glow 6 次/分、其余 API 50 次/秒。`$binary_remote_addr` 取自 TCP 连接不可伪造，比应用层按 `X-Forwarded-For` 限流可靠；**在 Cloudflare 后面时必须靠 `cloudflare_real_ip.inc` 还原真实 IP**，否则全站访客共用一个 CF 边缘 IP，会互相挤掉限额 |
| 头像上传 | 已加固 | 服务端重命名（不用客户端文件名，杜绝 `../` 与可执行后缀）、ImageIO 读魔数认格式、1MB 双拦（multipart + 业务层），Nginx `client_max_body_size 2m` 兜底；`/uploads/` 只读且由 nginx 单独转发到网关（不放静态目录，避免被长缓存规则截走） |
| 登录防爆破 | 已启用 | user-service 按规范化用户名在 Redis 中维护 15 分钟失败窗口，连续失败 5 次锁定 15 分钟，多实例共享状态 |
| JWT 撤销 | 已启用 | 登出或改密后，当前 JWT 摘要进入 Redis 直到自然过期；网关响应式检查，Redis 故障时返回 503 而不是放行撤销令牌。Redis 已开 AOF，重启不丢撤销记录 |
| Redis 业务缓存 | 已启用 | 公开文章/笔记及聚合读模型采用 30 秒至 5 分钟 TTL，写后按命名空间失效；完整用户资料、JWT 原文与持久业务明细不进 Redis |
| 容器权限 | 已加固 | 3 个 Java 服务均 `cap_drop: ALL` + `no-new-privileges:true`。容器仍以 root 运行：日志目录是 bind mount，会覆盖镜像内的属主设置，改非 root 需同步调整 `deploy/docker/logs/` 的属主 |
| 中间件暴露面 | **只绑回环** | MySQL / Redis / Qdrant / Nacos 与网关都只绑宿主机 `127.0.0.1`（公网与安全组均不可达），本地开发经 SSH 隧道访问（第十节）。公网入口只有一个：web 的 80/443 |
| 源站保护 | 安全组白名单 | 443 只放行 Cloudflare 网段 + 你的 IP（见第五节第 3 条）。
| JWT 密钥 | 启动即校验 | prod 下若密钥为空、少于 32 字符，或等于仓库中 dev 默认值，**直接拒绝启动**（common-core `SecretGuard`）。本编排对 3 个 Java 服务统一注入 `SA_TOKEN_JWT_SECRET`，密钥弱时整体拒绝启动（fail-closed） |

## 七、内存预算（8G 机器）

| 服务 | mem_limit | 说明 |
|---|---|---|
| mysql | 1g | `innodb-buffer-pool-size=256M`；数据量上来后可调到 512M |
| nacos | 900m | 单机模式，`JVM_XMX=512m` |
| qdrant | 512m | 尚未使用（M3 起接入） |
| redis | 512m | `maxmemory 192mb` + `noeviction`（宁可写失败，也不静默丢弃 JWT 撤销记录）；限额留给 AOF 重写的 fork |
| gateway | 512m | `-Xmx128m` + SerialGC |
| user-service | 512m | 同上 |
| content-service | 512m | 同上 |
| web | 64m | nginx 静态与反代 |
| **合计上限** | **≈4.5G** | 实际占用通常 2–3G，8G 机器跑完 M0–M11（AI 走云 API）仍有余量 |

> ⚠️ **mem_limit 不能贴着堆设**：`mem_limit ≈ JVM 堆上限 + 300MB 左右堆外开销`
> （Metaspace、Code Cache、线程栈、直接内存、GC 结构）。
> 曾经把 Java 服务设成 `256m` 而堆是 `-Xmx128m`，容器被 cgroup OOM **SIGKILL** 后由
> `restart: unless-stopped` 反复拉起——**日志里只有反复的启动记录，没有任何 Java 异常栈**，极易误判成
> 「服务起不来」。判断方法：
>
> ```bash
> docker inspect stellar-ink-user-service \
>   --format 'OOMKilled={{.State.OOMKilled}} ExitCode={{.State.ExitCode}} Restarts={{.RestartCount}}'
> docker stats --no-stream      # 看真实 RSS，再按上面规则反推合适的 mem_limit
> ```

三个 Java 服务统一 `-Xms32m -Xmx128m` + SerialGC，适合低并发个人博客；`256m` 上限已包含堆外内存。
若出现 `OOMKilled` 或 `OutOfMemoryError`，把对应服务的 `mem_limit` 调到 384m 并同步 `-Xmx256m`。
AI 阶段的 Python 服务建议再留 `512m`。

## 八、备份与恢复

备份脚本：`deploy/scripts/backup.sh`（MySQL 逻辑备份 + 上传文件 + `.env`）。

```bash
# 手动跑一次
bash deploy/scripts/backup.sh

# 顺带同步到异地（强烈建议：测试/备份机）
BACKUP_REMOTE=ubuntu@<测试机IP>:/data/stellar-ink/backups bash deploy/scripts/backup.sh

# cron（每天 03:00）
0 3 * * * BACKUP_REMOTE=ubuntu@<测试机IP>:/data/stellar-ink/backups /bin/bash /app/stellar-ink/deploy/scripts/backup.sh >> /data/stellar-ink/backups/backup.log 2>&1
```

- 备份落在 `/data/stellar-ink/backups/<日期_时间>/`（可用 `BACKUP_ROOT` 改），默认保留 7 份；
- **不备份** Qdrant（AI 阶段可由 MySQL 重建索引）、Nacos（prod 不 import Nacos 配置，注册表是临时数据）、
  Redis（缓存，丢了只是所有人重新登录一次）；
- `env.backup` 含 JWT 密钥与数据库口令，注意保管，别丢进公开位置。

**恢复步骤**：

```bash
cd /app/stellar-ink/deploy/docker
cp /data/stellar-ink/backups/<某次>/env.backup .env      # 先恢复 .env（密钥必须与库一致）
docker compose up -d mysql && sleep 30                    # 只起 MySQL
gunzip -c /data/stellar-ink/backups/<某次>/mysql.sql.gz | docker compose exec -T mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 stellar_ink
tar xzf /data/stellar-ink/backups/<某次>/uploads.tar.gz -C ./data    # 恢复头像
docker compose up -d                                      # 起其余服务
```

## 九、常见问题

- **端口被占用**：改 `.env` 的 `WEB_PORT` / `GATEWAY_PORT` / `NACOS_PORT`。
- **站点经 Cloudflare 报 525**：先读第五节第 5 条——**大陆源站 + 未备案域名**会按 SNI 拦截入站握手，
  跟 nginx/证书无关；用那三条 curl 命令 30 秒判定。
- **拉不动镜像**：配 `registry-mirrors`（第二节第 3 条），或本地 `docker save` 后 `docker load`。
- **业务服务起不来、报 MySQL 连接失败**：确认 `mysql` 容器 healthy（`docker compose ps`）、
  `.env` 里 `MYSQL_USER`/`MYSQL_PASSWORD` 与容器初始化时一致；`docker compose logs user-service` 看详情。
  注意：改 `.env` 里的 `MYSQL_USER`/`MYSQL_PASSWORD` **不会**改动已初始化过的数据卷，需要手工改库或重建卷。
- **业务服务健康检查 DOWN、Redis 连接失败**：确认 `redis` 容器在跑；若给 Redis 设了密码，
  同步改 `.env` 的 `REDIS_PASSWORD`，并把 compose 里 redis 的 healthcheck 改成 `redis-cli -a "$REDIS_PASSWORD" ping`。
- **网关反复重启**：多为 `SA_TOKEN_JWT_SECRET` 未设置或各服务密钥不一致，检查 `.env`。
- **业务服务报 `SecretGuard ... 拒绝启动`**：prod 下 JWT 密钥为空/过短/沿用了仓库 dev 默认值，
  用 `openssl rand -base64 48` 重新生成写入 `.env`。
- **Java 服务注册不上 Nacos**：`docker compose logs nacos` 看是否起来了（单机模式首次启动约 20–40 秒）；
  确认 `NACOS_ADDR=nacos:8848`、账号密码与 `NACOS_USERNAME`/`NACOS_PASSWORD` 一致；
  Nacos 2.x 客户端还需要 9848 端口，同编排网络内默认可达。
- **前端报 429**：触发 Nginx 限流（`limit_req`），阈值见 `nginx/default.conf`，按需调整。
- **磁盘吃紧**：查 `du -sh deploy/docker/logs/*`（日志上限 2G/服务，已从 20G 调小）、
  `docker system df`（构建缓存），定期 `docker builder prune`。
- **Sentinel dashboard 未部署**：网关会尝试上报 `localhost:8858`，连接失败只是无害告警。
- **Qdrant 接入**：业务暂未使用；M3 起直接在编排网络内以 `qdrant:6333` 访问。

## 十、本地怎么连服务器上的数据库与 Nacos

两条通路，别混：

**① 打开站点**：浏览器直接访问 `https://你的域名/`，走 Cloudflare，任何网络都能开，
**不需要**在安全组里放行你本机 IP（你本机的请求在 CF 边缘就终结了，源站只认 Cloudflare 网段）。

**② 本地连服务器上的 MySQL / Redis / Nacos / Qdrant**：这些服务只绑宿主机 `127.0.0.1`，
所以走 **SSH 隧道**——不用开任何端口，也不受你家里公网 IP 变动影响：

```bash
# 一条命令把网关、Nacos 控制台、MySQL、Redis、Qdrant 全部映射到本地
ssh -L 8080:127.0.0.1:8080 \
    -L 8848:127.0.0.1:8848 \
    -L 3306:127.0.0.1:3306 \
    -L 6379:127.0.0.1:6379 \
    -L 6333:127.0.0.1:6333 \
    ubuntu@<服务器IP>
```

> **本地端口被占用**（比如你本机已经装了 MySQL 占着 3306）时，把左侧端口换掉即可，
> 例如 `-L 13306:127.0.0.1:3306`，然后本地就连 `127.0.0.1:13306`。
> 隧道命令用 Git Bash / PowerShell 跑都一样；配好密钥登录后不会每次问密码。

连上之后，本地等价于：

| 本地地址 | 对应服务 | 连接参数 | 用途 |
|---|---|---|---|
| `localhost:3306` | MySQL | 用户 `stellar` / 密码＝`.env` 的 `MYSQL_PASSWORD` / 库 `stellar_ink`。⚠️ **不要用 root**：官方镜像的 root 默认只允许容器内 `localhost` 登录（`mysqladmin` 健康检查与备份脚本都走容器内，不受影响），本地工具请用 `stellar`（它在建库时被授予了 `stellar_ink.*` 的全部权限） | Navicat、DBeaver、HeidiSQL 连库；导入导出；排查数据 |
| `localhost:6379` | Redis | 无密码（容器内网才可达） | `redis-cli -h 127.0.0.1 -p 6379`；看缓存与 JWT 撤销列表 |
| `http://localhost:8848/nacos` | Nacos 控制台 | `nacos` / `nacos`（或你在 `.env` 里改的值） | 看服务注册、改配置 |
| `localhost:6333` | Qdrant | 无鉴权 | `http://localhost:6333/dashboard`；AI 阶段（M3 起）本地调检索 |
| `http://localhost:8080` | 网关 | — | 本地前端 `npm run dev` 会把 `/auth`、`/posts` 等前缀代理到这里（见 `stellar-ink-web/vite.config.js`），所以本地跑前端时**先开隧道就能直接连生产后端** |

**本地开发要用哪套数据，务必分清**：

| 目的 | 连哪台 | 原因 |
|---|---|---|
| 只读排查线上数据 | 生产（香港） | 只查不改；改数据请走接口或后台 |
| 写代码、跑测试、造数据 | **测试机（腾讯云那台）** | 本地 dev 直连生产库会把测试数据写进线上；测试机的库随便折腾 |
| 完全隔离 | 本地 Docker 起一套 | 见仓库根 `AGENTS.md` 的本地启动方式 |

**不要为了本地连库而放开安全组**：32/3306/6379/6333/8848 一个都不要对公网开，
隧道走 22（已限制只放行你的固定 IP）。真要临时直连源站排查，就把自己**当前**出口 IP 加进 443/80，用完立刻删。
