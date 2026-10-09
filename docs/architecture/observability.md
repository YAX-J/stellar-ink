# 可观测性（Prometheus + Grafana）

> 这份文档回答三个问题：**观测什么**、**怎么起来的**、**坏了怎么用它定位**。
> 它只记结论与口径，不记过程；排查过程写进本文末尾的「已知限制」与 `docs/troubleshooting.md`。

## 1. 边界：做什么、刻意不做什么

| 维度 | 决策 | 理由 |
|---|---|---|
| Metrics | **做**：Prometheus + Grafana + 告警规则 | 四个 Java 模块本来就有 `micrometer-registry-prometheus`、prod 档也已暴露 `/actuator/prometheus`，只差有人抓 —— 不做才是浪费 |
| Logs | **不动**：沿用 logback + `TraceIdFilter` | 已有 MDC、响应头 `X-Trace-Id`、响应体 `traceId`，单链路排查够用；引 Loki 是纯增量成本 |
| Traces | **不引** OTel / SkyWalking / Zipkin | 已拍板不部署（跨副本回放是**已知且被接受的限制**）；`traceId` 已能把「网关 → 服务 → Python」串起来 |
| 通知通道 | **默认关闭**，模板见 `observability/alertmanager/alertmanager.yml.example` | 必须填你自己的接收地址；而 Alertmanager 在配置校验失败时**拒绝启动** —— 挂成默认服务会让没填地址的人先遇到「整栈里有个容器起不来」 |
| Python 侧埋点 | **不做** | 它没有 `/metrics`（`app/rag/metrics.py` 是 recall@k / MRR 那类**评测**指标）。AI 侧可见性由 ai-service 聚合 `ai_call_log` + 探活 Python 得出 |

**安全姿态（不要破坏）**：三个业务服务的端口在 compose 里**刻意一个都没发布**（把 8101/8102/8107/8080 暴露到宿主机 = 同时绕过 nginx 边缘限流、并让 `/actuator/**` 匿名可达）。
Prometheus 用**服务名 + 容器端口**从编排内网抓取，因此它**不需要**任何服务发布端口；它自己也**不发布**端口（无鉴权，暴露公网等于公开全部运行指标）。
唯一对外开的是 Grafana，且只绑 `127.0.0.1`。

## 2. 拓扑

```
   SSH 隧道 ───────► ┌─────────────┐
   (仅回环)          │  grafana    │  127.0.0.1:3000   关闭匿名访问与自助注册
                     └──────┬──────┘
                            │ 查询
                     ┌──────▼──────┐
                     │ prometheus  │  编排内网 :9090   不发布端口
                     │ 15d / 1GB   │  scrape 30s
                     └──┬────┬───┬─┘
        ┌───────────────┘    │   └────────────────┐
        ▼                    ▼                    ▼
  gateway:8080        user:8101              ai-service:8107
  /actuator/prometheus content:8102         /actuator/prometheus
                                            （Python:8200 不抓）
```

`scrape_interval` 取 30s（不是默认 15s）：8G 机器上还有 MySQL/Redis/Nacos/Qdrant + 4 个 JVM + Python，采样密度换内存不划算；P95 用 `rate()` 算，30s 足够。

## 3. 埋点在哪、口径是什么

### 3.1 自动就有（零代码）

`jvm_memory_used_bytes` / `jvm_gc_*` / `process_uptime_seconds` / `process_cpu_usage` /
`http_server_requests_seconds_*` / `spring_cloud_gateway_requests_seconds_*`。

公共标签 `application=<spring.application.name>` 由 **两份等价实现**打上（改一处必须同步另一处）：

- `common-core` 的 `MetricsConfig` —— user / content / ai 三个服务（它们的启动类扫描 `com.stellarink.common`）
- 网关的 `GatewayMetricsConfig` —— 网关是 WebFlux，不能依赖带 servlet 的 common-core

为什么必须是 `MeterRegistryCustomizer` 而不是拿到注册表后再 `config().commonTags()`：JVM/进程指标在上下文早期就被注册，
晚补标签**不会报错**，只会让一部分曲线永远少一个标签、在看板上对不上。

### 3.2 缓存（`RedisCache`，common-core）

| 指标 | 标签 | 说明 |
|---|---|---|
| `stellar_cache_requests_total` | `namespace`、`result` | `hit` / `miss` / `error` / `bypass` |
| `stellar_cache_suspended` | — | Gauge 0/1：是否处于 30 秒失败熔断窗口 |
| `stellar_cache_evictions_total` | `namespace` | 精确删除 |
| `stellar_cache_invalidations_total` | `namespace` | 版本推进（整体失效） |

三条必须保持的口径：

1. **`bypass` 与 `miss` 必须分开**：前者是「Redis 不可用、本次根本没访问」，后者是「缓存里确实没有」。
   合成一个「缓存失败率」之后，这两件处理方式完全相反的事会看起来一模一样。
2. **版本号读取不计入命中率**：版本键从不被写入（不存在即视为 0 是**合法状态**），且每个请求都读一次。
   计进去会永久拉低整体命中率，看板上的数字就再也反映不了真实缓存效果。
3. **命名空间标签是必须的**：所有缓存共用一个 Redis，不分命名空间就只能看到总命中率，
   「post 列表命中率只有 5%」会被其它领域的好数字平均掉。命名空间取值 =
   各领域直接名称：`post` / `note` / `comment` / `tag` / `stats` / `meteor` / `echo` / `link` / `user`。

> 缓存是**故障放行**的（Redis 挂了就静默回源，业务不报错）。这是对的设计，
> 代价是「缓存挂了」只能靠 `stellar_cache_suspended` + 命中率发现 —— 日志里只有一行 warn。

### 3.3 网关鉴权（`RevokedTokenFilter`）

撤销校验是 **fail-closed** 的：Redis 查不通就回 503，绝不放行已撤销令牌。
安全口径正确，但**用户看到的是「莫名其妙 503」** —— 一次连接抖动、一个请求 503、下一个又好了，日志里只有一行 warn。
所以四种结果分开记：

| `stellar_auth_revoked_check_total{result}` | 含义 | 该往哪查 |
|---|---|---|
| `ok` | 查询成功且未撤销，放行 | — |
| `revoked` | 命中撤销列表，401（用户登出/改密，**正常**） | — |
| `error` | 两次都失败，fail-closed 503 | Redis 可达性与超时、网络质量 |
| `timeout` | 撞上 2 秒整体上限，fail-closed 503 | 响应式连接池被占满 / 连接卡死（与上一条**修法不同**，故必须分开） |

另有 `stellar_auth_revoked_duration_seconds{result}`（**连失败一起记**：P99 在抖动时顶到 2s 上限，
那正是「用户实际等了多久」的真实答案；只记成功路径会让看板一片祥和而用户在转圈）
与 `stellar_auth_revoked_retry_total`（**503 的前兆**：重试通常能跨过重连窗口，此时用户还没受影响）。

### 3.4 业务与数据库

| 指标 | 标签 | 说明 |
|---|---|---|
| `stellar_view_recorded_total` | `kind=post\|note`、`result=counted\|deduped` | 浏览闸门：登录用户按天去重，未登录每次计数 |
| `stellar_glow_recorded_total` | `result=created\|duplicate` | 点赞：`duplicate` = 登录用户重复点赞被唯一键挡下 |
| `stellar_db_pool_connections` | `state=active\|idle\|waiting\|max` | Druid 连接池（`DataSourceMetricsConfig`） |
| `stellar_quota_rejected_total` | `scope=concurrency\|user_calls\|user_tokens\|role_calls` | AI 配额触顶（调用前拦截，返回 429） |

浏览量那条有两个刻意的设计：

- **文章与笔记共用同一个指标名**，用 `kind` 区分（笔记与文章共用 `post_view` 闸门表，见
  `docs/architecture/README.md` §数据边界）。共用一个名字是因为看板上「今天有多少次浏览被计上」
  应当是一个数；拆成两个指标就得每次手工相加，而**漏加一个的症状是「浏览量看起来变少了」**，很难发现。
- **「本来就不该计」的情况不入指标**：文章不存在/未发射、笔记是私有或草稿、作者看自己 —— 这些不是「被去重」，
  混进 `deduped` 会让去重率虚高。

这两组「结果」标签里**没有一个是错误**，全是正常业务分支。压成 success/fail 就等于把信息丢掉：
「去重率突然变成 0」「重复点赞突然变多」都是**数据库里看不出异常**的行为变化，只有比例能暴露。

连接池那组对应一个真实踩过的坑（见 `docs/architecture/README.md` §空闲保活）：
跨公网连 MySQL 时 NAT 会丢空闲连接，半开连接占住池子直到 `socketTimeout` 才失败。
`active` 偏高 + `idle` 贴 0 + `waiting` 大于 0，是那个问题在爆发前的先兆。

### 3.5 AI 成本与下游可用性（`AiMetricsJob`）

| 指标 | 标签 | 说明 |
|---|---|---|
| `stellar_ai_daily_calls` | `role`、`scene` | 当日调用数（**含失败**） |
| `stellar_ai_daily_failures` | `role`、`scene` | 当日失败数 |
| `stellar_ai_daily_tokens` | `role`、`scene` | 当日 token（**只统计成功调用**：失败的用量本来就缺失） |
| `stellar_ai_daily_cost_cny` | `role`、`scene` | 当日**已定价部分**的成本（**元**，不是美元） |
| `stellar_ai_daily_unpriced` | — | 「有 token 但没配单价」的调用数 |
| `stellar_ai_daily_untokenized` | — | 「上游没回报 token」的调用数（Agent 与评测当前都属于这类） |
| `stellar_ai_python_available` | — | 0/1：Python 编排服务是否可用 |

四个必须保持的口径：

1. **成本单位是元（CNY）**，与 `ai_call_log` 的 `price_*` 列一致（原先看板上写「美元」是错的）。
2. **两个缺口分开暴露、且金额只是下限**：`unpriced` 是「知道用了多少但没配单价」，
   `untokenized` 是「根本不知道用了多少」。把它们当 0 计进金额会得到一个**偏低的假成本** ——
   那比报错更难发现。任一非 0 时 `cost.cny` 就只是下限。
3. **用 Gauge 而不是 Counter**：它们是「当日累计」、每天零点归零；Counter 只能单调递增，
   跨天手工重置漏一次就会得到一条只增不减、看起来完全正常的曲线。
4. **`MultiGauge` 必须 `strongReference(true)`**：默认是弱引用，而我们每分钟新建一批 Row，
   弱引用会让指标在 GC 之后**静默消失**（看板隔一段时间空一块，且没有任何报错）。

**为什么这些数从 `ai_call_log` 反推，而不是给 Python 加 `/metrics`**：AI 每次调用都花钱，
而「花了多少」的唯一真相源是 Java 侧的调用账（身份、角色、单价快照、失败分类都在那边，Python 没有身份）。
给 Python 再加一套只会得到**第二个口径** —— 两个看板给出两个成本数字是最难解释的一类问题。
成本计算因此**复用** `AiUsageService.dailyUsage()`，那里面的 `totalsOf` 是全仓库唯一的口径实现。

## 4. 告警规则

规则文件：[`deploy/docker/observability/rules/stellar-ink.yml`](../../deploy/docker/observability/rules/stellar-ink.yml)（Prometheus 每 30s 求值一次）。

| 告警 | 阈值 | 为什么是它 |
|---|---|---|
| `StellarInkServiceDown` | `up == 0` 持续 2m | 最基础，兜住所有「没起来 / 没注册进 Nacos / 崩了」 |
| `StellarInkProcessRestarted` | `process_uptime_seconds < 300` 持续 1m | 对应「容器被 cgroup OOM SIGKILL 后日志里看不到任何异常栈」那个坑；正常发布也会触发，故为 info |
| `StellarInkJvmHeapNearLimit` | 堆使用率 > 90% 持续 10m | mem_limit 512m + `-Xmx128m`，堆打满是被 cgroup 杀而不是抛 OOM |
| **`StellarInkRevokedCheckFailing`** | 5m 内 `error\|timeout` 出现任意一次 | **最重要的一条**：fail-closed 是安全边界，一次都不该有 |
| `StellarInkRevokedCheckRetrying` | 10m 内重试 > 3 次 | 503 的**提前量**：此时用户还没受影响 |
| `StellarInkGatewayLatencyHigh` | 网关 P95 > 1s 持续 5m | 网关是必经之路，它慢等于全站慢 |
| `StellarInkHigh5xxRate` | 5xx 占比 > 1% 持续 5m | 用户可见故障 |
| **`StellarInkCacheSuspended`** | `max(suspended) > 0` 持续 5m | **缓存层唯一的静默故障信号**（Redis 挂了业务不报错，日志只有一行 warn） |
| `StellarInkCacheHitRateLow` | 命中率 < 50% 持续 15m | 失效风暴 / TTL 过短 / 参数维度太细 |
| `StellarInkDbPoolWaiting` | `waiting > 0` 持续 2m | 池子不够、慢 SQL、或半开连接占池的先兆 |
| `StellarInkPythonUnavailable` | `python.available == 0` 持续 3m | 博客读写不受影响，但问答/写作/Copilot 全挂 |
| `StellarInkAiQuotaRejectedSpike` | 10m 内 > 20 次 | 有人在刷，或额度配小了 |
| `StellarInkAiCostHigh` | 当日成本 > 30 元 | **占位阈值**，按你自己的单价改 |

**求值与通知是两件事**：Prometheus 会求值这些规则并在 `/alerts` 页与 HTTP API 里如实暴露 firing 状态；
要**收到通知**，在服务器上二选一 —— ① 启用 `observability/alertmanager/alertmanager.yml.example`（改名为 `.yml` 并填地址，同时放开 compose 里那两行注释）；
② 在 Grafana 里配一次 contact point。默认都没开，理由见 §1 与 §6。

## 5. 看板

`deploy/docker/observability/grafana/dashboards/` 下四块，全部走 **file provisioning** 自动加载：

| 文件 | 看什么 |
|---|---|
| `00-overview.json` | 存活数 / QPS / 5xx 率 / P95、各服务 QPS 与 P95、JVM 堆、`up`、进程运行时长 |
| `10-gateway-auth.json` | 撤销校验四档结果与 P95/P99、**503 归因**、重试次数、路由 QPS、状态码分布 |
| `20-content-cache.json` | 各命名空间命中率、结果分布、熔断状态与历史、失效/淘汰、浏览与点赞去重、Druid 池 |
| `30-ai-cost.json` | 成本（元）/调用数/token/失败数、按 role 与 scene 分组、**两个成本缺口**、配额触顶、Python 可用性 |

⚠️ **看板必须在仓库里**：界面里手点的面板存在 `grafana-data` 卷里，容器一重建就没了 ——
那是「演示过一次」，不是「可复现的观测栈」。`allowUiUpdates: false` 是有意的。

## 6. 已知限制（不是缺陷）

1. **通知默认不生效**（见 §4）。这是刻意的：需要你的接收地址，而填错会让容器起不来。
2. **`/actuator/prometheus` 在四个服务里都是匿名可读的**（网关自身的 actuator 也在 `:8080` 上）。
   安全性完全依赖「端口不发布到宿主机」这一条 —— 所以**任何情况下都不要为了让 Prometheus 抓取而发布 8101/8102/8107/8080**。
   nginx 也不代理 `/actuator`（`location /` 只做 SPA 回退，`/actuator/prometheus` 会落到 `index.html`）。
3. **AI 成本指标是「当日快照」**（每分钟重算一次的阶跃值），不是事件计数；跨天归零属于预期。
   进程重启后的第一分钟内，`stellar_ai_daily_*` 会短暂缺失（还没有第一次刷新）。
4. **跨副本的 trace 回放查不到**（进程内缓冲）—— 这是 M8/E3-4 就已拍板接受的限制，可观测性栈没有改变它。
5. **Prometheus 保留 15 天 / 1GB**：它不是长期存储。要长期留存得换远端存储，当前规模不需要。

## 7. 运维速查

```bash
# 起观测栈（在 deploy/docker 下；.env 里需有 GRAFANA_ADMIN_PASSWORD）
docker compose up -d prometheus grafana

# 看板：SSH 隧道后浏览器打开 http://127.0.0.1:3000
ssh -L 3000:127.0.0.1:3000 ubuntu@<服务器>

# 抓取目标是否全绿（不需要发布 9090）
docker compose exec prometheus wget -qO- 'http://localhost:9090/api/v1/targets?state=active' | head -c 400

# 当前 firing 的告警
docker compose exec prometheus wget -qO- 'http://localhost:9090/api/v1/alerts'

# 本机验证埋点（dev 档已暴露 prometheus；经 SSH 隧道到 8080/8101/8102/8107）
curl -s http://127.0.0.1:8080/actuator/prometheus | grep -c '^stellar_'

# 改完规则热加载（compose 里已开 --web.enable-lifecycle）
docker compose exec prometheus wget -q --post-data='' -O- http://localhost:9090/-/reload

# 手工触发一个 503 归因（演练用）：停 Redis，观察
#   stellar_auth_revoked_check_total{result=~"error|timeout"} 上升 + stellar_cache_suspended=1
docker compose stop redis && sleep 60 && docker compose start redis
```

## 8. 改这块之前先看

- **指标名与标签是契约**：改了名字等于改了所有看板与告警规则（它们散在 `rules/` 与 4 个 JSON 里）。
  新增指标时同步：本文档 §3 → 看板 → 规则。
- **标签基数红线**：禁止把 `userId`、`traceId`、原始 path（`/posts/123`）、搜索关键词、文章 id 用作标签。
  基数失控的监控比没有监控更糟 —— Prometheus 会先 OOM。
  （`http_server_requests` 的 `uri` 是模板化的 `/posts/{id}`，这是 Spring Boot 的默认行为，但**抽查过一次**再改。）
- **网关与 common-core 各有一份公共标签配置**，改一处要同步另一处。
- **`application-*.yml` 的 actuator exposure**：prod 档是 `health,info,prometheus`，dev 档是 `health,info,metrics,loggers,prometheus`
  （dev 两个都给：`metrics` 是 JSON 便于本机 curl 单个指标，`prometheus` 是为了能用与生产**同一份** scrape 配置验证接入）。
  改 exposure 时注意远端 Nacos 上也有同名模板（虽然实测它**不覆盖**本地 yml，但按仓库口径两处都改）。
- **测试档（`unittest`）关掉了 `stellar.ink.metrics.enabled`**：同 `quota.enabled` 的理由 ——
  定时任务会在测试进程里每 60 秒查库并探活 Python，把真正的信号淹没在日志里。
  指标逻辑由单测直接调 `refresh()` 覆盖，不依赖调度器。
