# 星笺后端架构（Spring Cloud Alibaba 微服务）

> 版本矩阵：Spring Boot 3.2.12 / Spring Cloud 2023.0.6 / Spring Cloud Alibaba 2023.0.3.4 / Java 17 / MyBatis-Plus 3.5.15。
> 对外 API 路径保持不变，前端无感。

## 拓扑

```text
                       ┌──────────────────────────────┐
  前端 / curl ─────────▶│ gateway-nacos-sentinel :8080 │
                       │ Sa-Token 鉴权 / CORS / 限流   │
                       └──────────────┬───────────────┘
                                      │ 显式路由（lb://）
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
             user-service :8101  content-service :8102  ai-service :8107
             用户、认证、角色     post / comment / note   对外 /ai/** 出口
                    │            meteor / echo / link   （鉴权复核、配额、审计）
                    │            stats                        │
                    └────────────┬────────────┘              │ HMAC 内网签名
                          ┌──────┴──────┐                     ▼
                          ▼             ▼            stellar-ink-ai :8200
              MySQL（共享 stellar_ink） Redis          Python：模型网关 / RAG / Agent
                                                     （仅内网可达，浏览器不直连）

  四个 Java 服务均注册到 Nacos :8848（注册中心 + 配置中心）；
  Python 不注册 Nacos，地址由 ai-service 的 python-base-url 固定配置
```

> **AI 当前进度**：拓扑里的每条线**都已接线** —— 网关有 `/ai/**` 路由（`gateway` 的
> `application-dev.yml`）、`ai-service` 有 `@EnableFeignClients` + 内部 HMAC 签名拦截器，
> 问答 / Copilot / 评测 / 画像 / Agent 的对外出口与前端消费方均已完成。
> 逐阶段核验证据与已知缺口见 `docs/ai/status.md`，整体进度见 `docs/status.md`。

## 模块结构

```text
stellar-ink-server/
├── pom.xml
├── common-components/
│   ├── shared-model/          Response、异常、跨模块 DTO/VO（含 ai 子包）
│   └── common-core/           异常处理、TraceId、MyBatis-Plus、鉴权辅助、Redis 工具
├── gateway-nacos-sentinel/    API 网关（8080）
├── user-service/              用户与认证（8101）
├── content-service/           内容聚合服务（8102）
│   └── com.stellarink.content/
│       ├── post/              文章、标签、搜索
│       ├── comment/           文章评论（公开读取、登录发表评论）
│       ├── note/              技术笔记（结构化 / 可私有 / 可验证）
│       ├── meteor/            流星备忘录
│       ├── echo/              回声漂流瓶
│       ├── link/              星链友链
│       └── stats/             写作脉搏
├── stellar-ink-ai-client/     Java → Python 内部客户端契约（Feign 接口 / DTO / 降级，M0-3 起）
└── ai-service/                AI 业务服务（8107，M0-4 起）
    └── com.stellarink.ai/{controller,client,config,service}

stellar-ink-ai/                Python AI 编排服务（8200，M0-1 起；仅内网可达）
├── app/{main.py,api/v1,core,schemas,providers,embedding,rag,agents,vectorstore}
└── tests/{test_*.py,fixtures/}   fixtures 与 Java 契约测试共用同一组 JSON
```

> `post`（星/文章）与 `note`（标本/技术笔记）是两张独立表：文章重文笔、天然公开；
> 笔记结构化、**可私有**（`visibility`）、会过期（`verified_at`）。两者边界不同，因此不合并为一张表。

**AI 边界**（与其它两个业务服务的区别）：ai-service 不拥有任何业务表，
**没有数据源与 Redis 依赖**（`DataSourceAutoConfiguration` 与公共模块的 MyBatis-Plus/Redis Bean 被显式排除），
也不直接读写 `post`/`user`。文章数据将来由 content-service 的内部契约提供（M3），
草稿只随当前作者请求临时传输。

**AI 能力的代码归属**：模型调用与厂商 SDK、Prompt、结构化输出校验、切块与嵌入、检索与重排、
引用组装、Agent 与工具、记忆、GraphRAG、评测 Trace **一律写在 Python（`stellar-ink-ai`）**；
Java 的 `ai-service` 只负责鉴权与角色、配额与审计、HTTP/SSE 协议转换、超时取消降级与 DTO 映射，
不出现任何 AI 算法或厂商 SDK 类型。判断标准：换成另一个模型或检索策略就要改的代码属于 Python。

AI 技术路线与实施顺序见
[docs/ai/README.md](../ai/README.md) 与 [docs/ai/implementation-roadmap.md](../ai/implementation-roadmap.md)，
每轮开发流程见 [docs/ai/development-workflow.md](../ai/development-workflow.md)。

## 为什么收敛为两个业务服务

- 流星、回声、友链原服务都只有一个控制器、一个 Mapper 和一个实体，独立 JVM 与连接池的成本远高于隔离收益。
- 写作统计只消费文章数据，原先通过 Feign 获取摘要会增加一次网络调用、熔断配置和故障点。
- 项目使用同一个 MySQL 数据库，现阶段没有独立扩缩容、独立发布或独立数据源的实际需求。
- 合并只改变运行单元，不改变领域边界。content-service 内仍按领域分包，后续达到独立扩缩容或团队所有权门槛时可重新拆分。

## 路由

| 服务 | 路由前缀 |
|---|---|
| user-service | `/auth/**`、`/user/**`、`/uploads/**` |
| content-service | `/posts/**`、`/notes/**`、`/tags/**`、`/search/**`、`/meteors/**`、`/echos/**`、`/links/**`、`/stats/**` |
| ai-service | `/ai/**`（含 `POST /ai/qa`、`/ai/qa/stream`、`/ai/writing/**`、`/ai/admin/**`；`/ai/health` 公开） |

网关关闭 discovery locator，只允许显式路由，防止通过 `/{serviceId}/**` 绕过鉴权。`/internal/**` 不对外路由。
`/uploads/**` 是 `user-uploads` 路由（指向 user-service 的静态资源映射），用于头像等上传文件的**匿名读**；
上传/删除本身走 `/user/avatar`，受网关鉴权保护。
`/ai/**` 是唯一进 Python 的路径：Python 的 8200 端口**不配网关路由、不暴露公网**，
只能由 ai-service 在编排网络内调用（M1 起带 HMAC 签名头）。

## 鉴权

- user-service 负责登录、注册和 JWT 签发。
- 网关按路径和角色进行第一层校验；业务服务使用 `AuthHelper` 复核登录身份与角色。
- `READER` 可读和公开互动，`AUTHOR` 可维护自己的文章与流星，`ADMIN` 可管理全部内容、友链状态和用户角色。
- 友链使用 `0 待审核 / 1 已接入 / 2 已驳回` 三态；公开 `/links` 只返回已接入项，
  `/links/pending` 在网关 GET 放行规则之前单独校验 ADMIN，内容服务再做一次角色复核。
- JWT 无状态验签，网关与两个业务服务必须使用同一 `SA_TOKEN_JWT_SECRET`。
- JWT 仍是无状态主体，但登出/改密会把当前令牌摘要写入 Redis 撤销列表；网关用响应式 Redis 在路由前统一拦截，键只保存 SHA-256 摘要并随 JWT 到期删除。
- **笔记的私有隔离不在网关**：网关对所有 GET 放行，`PRIVATE` 笔记的可读性由 content-service 的
  `NoteServiceImpl#ensureReadable` 判定（非作者一律 404，ADMIN 也不能读他人私有笔记）；
  归属判定 `ensureOwned` 比文章更严格，只有作者本人能改删。
- `/notes/review` 与 `/notes/mine` 都可能返回作者的私有内容，因此网关必须在 GET 公开放行规则之前
  校验 AUTHOR；content-service 再复核角色和 `user_id`。复核状态不落新列，由 `verified_at` 按 180 天实时派生。

## 数据边界

| 服务 | 负责的数据 |
|---|---|
| user-service | `user` 表；头像图片文件（本地磁盘 `UPLOAD_DIR`，生产由 Docker 卷持久化） |
| content-service | `post`、`post_glow`、`post_comment`、`note` 表，`meteor`、`echo`、`link`，以及基于 `post` 的实时统计 |
| 跨内容类型共用 | `post_view`（浏览计数闸门：只记「某用户某天已计一次」，与内容类型无关，文章与笔记共用） |

> 头像存储有**两种实现**（`stellar.ink.storage.type` 切换）：
> `local` 本地磁盘（文件与库必须同机可达，多实例或本地连远程库会出现「上传成功但图片 404」）；
> `cos` 腾讯云对象存储（生产用香港桶，图片域名经 Cloudflare Worker 代理暴露，见 `deploy/cloudflare/README.md`）。
> 方案、部署形态、迁移与回滚见 [avatar-minio.md](avatar-minio.md)。

当前使用一个 `stellar_ink` 数据库。服务之间不直接访问对方负责的表，也没有同步服务调用。统计逻辑与文章同进程，直接通过 `PostMapper` 查询已发布文章。

## 空闲保活：远端 Redis / MySQL 都躲不开的那件事

**症状**：本地开发（跨公网连服务器上的 Redis 与 MySQL）时，「一段时间不操作」之后的
第一批请求会失败或卡死 —— 网关回 503（fail-closed），业务服务回 500，
或者请求卡满浏览器自己的 15s 超时（DevTools 里显示 `(canceled) @15s`，日志里什么都没留下）。

**根因（逐条实测，不是推测）**：

1. **不是 Redis/MySQL 掐连接**：`CONFIG GET timeout` = 0、`SHOW VARIABLES LIKE 'wait_timeout'` = 28800；
2. **是中间链路丢了空闲连接**：本机 `netstat` 还写着 ESTABLISHED，服务端 `CLIENT LIST` /
   `information_schema.processlist` 里也还列着，但那条 TCP 流已经被 NAT/防火墙丢弃 ——
   实测 Redis 侧 11 条对 6 条、MySQL 侧 34 条里 24 条 `Time` 上千秒且**从没被 ping 过**；
3. **于是下一个命令写进了黑洞**：要等命令/socket 超时才发现
   （Redis 500ms → 503；JDBC `socketTimeout` 15s → 卡 15 秒后换连接成功）；
4. **连接池自带的「验活」救不了**：Lettuce 的池化工厂 `validateObject()` 只是
   `StatefulConnection.isOpen()`（本地标志位），半开连接照样返回 true，校验不发任何网络包；
   Druid 的 `keep-alive` 默认门限是空闲 30 分钟、探测间隔 2 分钟，都晚于链路丢包时间。

**对策：让链路永不空闲（真发命令的那种心跳）** ——

| | Redis | MySQL |
|---|---|---|
| 谁发心跳 | `RedisKeepAliveHeartbeat`（common-core 与网关各一份） | `DataSourceKeepAliveHeartbeat`（common-core） |
| 怎么发 | 每 30s 借一条池化连接 `PING` | 每 30s **同时借出 initial-size 条**连接、各跑一次 `SELECT 1` |
| 池的配合 | `max-idle: 1` / `min-idle: 0`（业务借到的就是被热过的那条） | 保留 Druid `keep-alive` 作为兜底 + `min-evictable-idle-time: 30s` |
| 失败怎么办 | `LettuceConnectionFactory#resetConnection()` 丢掉整池 | 只记日志，坏连接交给 Druid 淘汰 |
| 生效值从哪看 | 启动日志「保活心跳已启动」 | 启动日志「MySQL 空闲保活已生效[…]」+「MySQL 保活心跳已启动：每 30000 ms 同时热 N 条连接」 |

⚠️ **为什么 MySQL 这边不能只靠 Druid 自带的 `keep-alive`**（实测）：它在 `shrink()` 里
只把「超出 `minIdle` 的、以及最近使用的那几条」纳入保活，而且扫描时一旦遇到一条
「既不够旧到淘汰、又不够旧到保活」的连接就直接 `break`。结果 MySQL 侧能看到
**一部分连接每十秒被 ping（`time` 归零）、另一部分闲置上千秒** ——
业务请求恰好借到后者时，命令写进被丢弃的连接，要等 JDBC `socketTimeout`（15s）才失败换连接，
页面就是「刷新后卡 15 秒」。所以我们自己发心跳，并且**必须同时借出多条**：
Druid 的借用是 LIFO（取最近归还的那条），一条一条借还只会反复热同一条连接。

⚠️ 两个坑：**① 心跳必须打在实际被用的那个池上** —— Spring Data Redis 的阻塞路径（`getConnection`
→ commons-pool2 的 `pools`）与响应式路径（`getConnectionAsync` → Lettuce 的 `asyncPools`）
是两个独立的池，网关的撤销校验走后者，用错 API 会「心跳日志一切正常但照样 503」；
**② 这几组参数在远端 Nacos 上也有同名键且优先级更高**，所以 `DruidKeepAliveConfig` 在代码里
定死并打印生效值，避免「改了本地 yml 却白改」。

**验收办法**（不用等页面点按）：
`SELECT time FROM information_schema.processlist WHERE host LIKE '<本机公网IP>%'` ——
所有连接的空闲时间都应当 ≤ ~60 秒；Redis 侧 `CLIENT LIST` 里我们的连接应当反复出现
`cmd=ping`（`idle` 周期性归零）。

⚠️ **生产 Docker 编排里不存在这个问题**：Redis/MySQL 与应用同机同网，没有 NAT 丢包。
这是**本地跨公网连远端中间件**特有的代价。

### 本地开发现状（2026-09-25 起：能本机的都本机）

`deploy/scripts/start-all.bat` 的默认值已经改成 **本机 Redis + 本机 MySQL**，
只有 Nacos 留在远端（它的客户端每 ~30s 长轮询一次，链路不会空闲，而且这个命名空间的动态配置在那边）：

| 中间件 | 本地开发现状 | 依据 |
|---|---|---|
| Redis | **127.0.0.1:6379**（`D:\Redis`，Windows 服务、开机自启、无密码、`timeout 0`） | 换之前 `USE_REMOTE_REDIS=1` 可回到远端 |
| MySQL | **127.0.0.1:3306**（本机 MySQL 8.0.45，库 `stellar_ink`） | 换之前 `USE_REMOTE_MYSQL=1` 可回到远端 |
| Nacos | 远端 `124.221.158.32:8848`（命名空间不变） | 长轮询自带心跳；本机也有 `tools\nacos` 可随时改本地 |

**三套环境的归属（2026 口径；权威记录见 `AGENTS.md` §2「环境矩阵」）**：

| 环境 | 地址 | 说明 |
|---|---|---|
| 开发 dev | `127.0.0.1` | 本机 MySQL / Redis / Nacos；本节所说的「本机」即此 |
| 测试 test | `124.221.158.32` | 本文档其余章节里写作「远端服务器」「服务器上的」的，指的都是这台测试机 |
| 生产 prod | `103.14.33.78` | `deploy/docker` 整栈；中间件只绑宿主机回环，本机经 SSH 隧道访问 |

⚠️ 测试与生产是**两台不同的机器**：改运维口径、防火墙规则、部署步骤时别把两者混为一谈。

**隔离现状（2026-09 实测，别当成已分开）**：MySQL / Redis 三层是分开的，但 **Nacos 是「开发 = 测试」共用** ——
`application-dev.yml` 默认 `NACOS_ADDR=124.221.158.32:8848` + 命名空间 `f0350c82-…`，
与测试环境同机同空间（生产是 `103.14.33.78` + `140e3d39-…`）。因此本机启动的服务
**注册在测试环境的注册中心里**：测试机一旦也跑起同名服务，网关 `lb://` 会在两边实例间轮询，
而两边连的又不是同一个 MySQL，表现为「接口时好时坏、数据对不上」。测试机目前只跑中间件
（8080/8101/8102/8107/8200 全未监听），所以这个坑还没被触发。要真正分开：
本机起 `tools/nacos` 让 dev 指向 `127.0.0.1:8848`，测试环境另用独立命名空间。

**三档 profile（2026-09 起）**：`application-{dev,test,prod}.yml` + `nacos-application-{dev,test,prod}.yml`，
每服务 8 件。`test` 档面向「应用与中间件同机跑在测试机」：`NACOS_ADDR`/`MYSQL_HOST`/`REDIS_HOST`
全默认 `127.0.0.1`，`SA_TOKEN_JWT_SECRET` 与 `MYSQL_PASSWORD` 必须显式注入
（⚠️「无默认值」**不等于** fail-fast，见 `AGENTS.md` §5「安全」的实测结论），
起法 `java -jar xxx.jar --spring.profiles.active=test`。
⚠️ 单测用的 profile 名是 **`unittest`**（`src/test/resources/application-unittest.yml`，H2 内存库）：
`test` 已被测试环境档占用，两者同名会互相遮蔽（同名资源只取 classpath 第一个）且**不报错**。

本机库是把远端库补齐过来的：`deploy/sql/10_ai-schema.sql` + `11_ai_model_library.sql`
建好本机缺的 5 张 `ai_*` 表，再把 `ai_provider_config` / `ai_model` 的数据搬过来
（密钥列是用本机 `.env` 里的 `AI_SECRET_MASTER_KEY` 加密的，所以搬过来仍能解密）。
E3-1 起又多了一张 `ai_call_log`（AI 调用账）与角色单价两列，脚本是 `12_ai_call_log.sql`
（**幂等**：`CREATE` 用 `IF NOT EXISTS`、`ALTER` 走 `information_schema` 判断，可重复执行）。

E4 的 LLM Wiki 又添了三组表，各自一个脚本：

| 脚本 | 表 | 幂等锚点（**没有它就别指望重复构建不出脏数据**） |
|---|---|---|
| `13_ai_wiki.sql` | `ai_wiki_claim`（带证据的主张） | `(post_id, content_hash, claim_text)` |
| `14_ai_wiki_entity.sql` | `ai_wiki_entity` / `ai_wiki_entity_mention` / `ai_wiki_relation` / `ai_wiki_relation_evidence` | 实体 `normalized`；提及含 `claim_text`；关系 `(source, target)` 且**两端按 id 排序**（无向边只有一种表示） |
| `15_ai_wiki_topic.sql` | `ai_wiki_topic` / `ai_wiki_topic_entity` / `ai_wiki_topic_evidence` | 主题**成员签名**（成员规范化名字排序后的 SHA-256），**不是主题名** —— 名字由成员算出来，成员一变名字就变 |

⚠️ 这三张脚本**没执行时，阅读页的「知识条目 / 本文提到的实体 / 本文参与的主题」三块会静默不显示**
（前端按「辅助信息失败不损伤主流程」降级），现象上就是「功能看着没上线」，日志里只有一条「表不存在」。

⚠️ **两份数据不再同步**：本地写的内容不会上服务器，服务器上的新内容也不会下来。
要发布内容仍然必须连远端库（`set USE_REMOTE_MYSQL=1` 再跑 `start-all.bat`）。

心跳/保活那一整套在本地连线下其实用不上了（本机没有 NAT），但保留着 ——
一旦把开关拨回远端就是现成的保护，代价只是每 30s 几条 `PING` / `SELECT 1`。

### 重启后第一个请求为什么慢 5 秒（JCE 预热）

症状：服务重启后，**第一个需要验签或加密**的请求要 5~6 秒，之后同类请求只要几十毫秒。
与「空闲」无关（当天同一个 JVM 里相隔 34 分钟的两次 5 秒卡顿，都是各自重启后的第一个鉴权请求），
也**与数据库无关**——公开接口（不解析 JWT）一直很快：

```
21:05:55  GET /ai/health          200   22ms   ← 重启后第一个请求，公开接口
21:06:15  GET /ai/admin/models    200 5379ms   ← 第一个需要验签的请求
21:07:52  GET /ai/admin/models    200   16ms   ← 之后一直这么快
```

两个并发的鉴权请求还会在同一毫秒一起解开，这是「一起等同一把锁」的特征。
线程 dump（请求卡住时 `jcmd <pid> Thread.print`）直接给出结论：

```
javax.crypto.Mac.getInstance
  javax.crypto.JceSecurity.getVerificationResult / ProviderVerifier.verify
    javax.crypto.JarVerifier.verifyJars → verifySingleJar
      org.springframework.boot.loader.zip.ZipContent.getEntry   ← 正在逐条读可执行 fat jar
        sun.nio.ch.FileDispatcherImpl.pread0
```

第一次用到某个算法时，JVM 要校验**调用方所在 jar 的签名信息**（JCE 的老规矩）。
我们跑的是 Spring Boot 可执行 fat jar，`JarVerifier` 只能顺着嵌套 jar 的中央目录
**一条条随机读**，于是这个本来微不足道的校验被放大成数秒；`JceSecurity` 按 provider
缓存校验结果，所以只疼第一次。

对策是**预热**，不是「关掉校验」（JCE 没有官方开关，拆 fat jar 代价更大）：
`common-core` 的 `JceWarmupRunner` 与网关的同名实现（WebFlux 不能依赖 common-core，
**改一处要同步另一处**）在启动期真跑一遍：

| 预热项 | 覆盖的生产路径 |
|---|---|
| `Sa-Token-JWT(create+parse)` | **这一项才是那 5 秒的正主**：Sa-Token 的签/验走 Hutool |
| `HmacSHA256` | ai-service → Python 的内部签名（我们自己的调用路径） |
| `AES/GCM/NoPadding` | `AesGcmCipher` 加解密模型 API Key |
| `SHA-256` | 令牌摘要（撤销列表）与内部签名里的 body 摘要 |

两条实测教训（都踩过）：

1. **只从我们自己的类里调 `javax.crypto` 没用**：第一版就这么写，启动日志显示预热「耗时 0~12ms」，
   而重启后第一个鉴权请求照样 9.6 秒（网关验签 5s + ai-service 再验签 5s）。
   原因是 `JarVerifier` 校验的是**调用方所在的那个 jar** —— 我们自己的 jar 只有几十个类（便宜），
   而 Hutool 是 fat jar 里一个 **2.5MB 的大 jar**（逐条读中央目录才那么慢）。
   所以预热必须走**生产同一条入口**（`SaJwtUtil.createToken` + `parseToken`），由单测钉住。
2. **`SaJwtUtil.createToken(payloads, key)` 这个重载不带有效期**，解析时会被判
   「jwt 已过期」而抛 `SaJwtException` —— 必须用带 `timeout` 的全参重载。
   这类「预热自己失败了但业务照样跑」的静默失败，靠日志很难发现，
   所以测试断言的是 `warmUp()` 的返回值必须**等于** `ALGORITHMS`（少跑一项就红）。

启动日志会记录耗时（fat jar 下就是那几秒被提前付掉）：

```
JCE 预热完成：[Sa-Token-JWT(create+parse), HmacSHA256, AES/GCM/NoPadding, SHA-256] 耗时 5xxx ms
```

`ApplicationRunner` 在 Tomcat/Netty 已开始接受请求**之后**执行，所以健康检查与 Nacos 注册
不受影响；万一有请求恰好在这几秒里进来，它只是和预热一起等同一把锁（总量不变、不会更慢）。
⚠️ 这个故障**只在可执行 fat jar 里出现**：扁平 classpath（IDE / 单测）下预热耗时是 `0ms`，
别据此以为没事。

排查同类问题的手法（本次就是这么做出来的）：起一个 `--server.port=8307
--spring.cloud.nacos.discovery.enabled=false` 的临时实例（不注册 Nacos，网关不会路由到它），
第一个请求发出后立刻 `jcmd <pid> Thread.print`，看那个 `http-nio-*-exec-*` 线程
到底卡在哪一帧；服务自己的 `API-ACCESS` 日志（`LogInterceptor`）用来看**服务端**耗时，
别用客户端的数字下结论（`curl` 参数被 shell 拆错、PowerShell 的 `Invoke-RestMethod`
首次调用开销都会伪装成「服务慢」）。

### Nacos 动态配置：本机上的两个坑（2026-09-25 实测）

远端 Nacos（2.4.3，命名空间 `f0350c82-…`）上存在 `user-service-dev.yaml`（2429 字节）与
`gateway-nacos-sentinel-dev.yaml`（1437 字节），`content-service` / `ai-service` 的 dataId
**不存在**（HTTP 404）。这两份配置踩了两个坑，且都是**静默**的：

**坑一：整份配置因为字符集解析失败而根本没生效。** 启动日志里是：

```
ERROR NacosConfigDataLoader - Error getting properties from nacos: … dataId='user-service-dev.yaml'
org.yaml.snakeyaml.error.YAMLException: java.nio.charset.MalformedInputException: Input length = 1
```

而导入写的是 `optional:`，所以**失败只留一行 ERROR，服务继续用 `application-dev.yml` 跑**，
表现得就像那份 Nacos 配置不存在。原因不在 Nacos 服务端：
`Content-Type: text/plain;charset=UTF-8`、内容也是合法 UTF-8（严格 UTF-8 解码通过），
但把这些字节按 **cp936（GBK）严格解码**会在**第 19 字节**抛错 —— 那正是第一处中文注释的位置，
与 Java 的 `Input length = 1` 形态一致。也就是说**解码用了 JVM 默认字符集，而这台中文 Windows 上
JDK 17 的默认字符集是 GBK**（JDK 18+ 起 `file.encoding` 默认才是 UTF-8，Linux 容器通常也是 UTF-8，
所以这是**本机开发独有**的问题）。

对策与验证（临时实例、端口 8301、不注册 Nacos，逐个变体独立工作目录）：

| 变体 | 结果 |
|---|---|
| 基线 | 失败（复现） |
| `--spring.cloud.nacos.config.encode=UTF-8` | **仍失败**（这个键救不了） |
| `-Dfile.encoding=UTF-8` | **修好，配置正常加载** |
| 两者都加 | 修好（起作用的是 JVM 参数） |

`start-all.bat` 的 4 条 java 启动线因此都带上了 `-Dfile.encoding=UTF-8`，
**删掉它 gateway / user-service 会重新静默忽略各自的 Nacos 配置**。

**坑二：同名键仍然不是 Nacos 赢。** 修好字符集之后解析不再报错，
但**不能**据此认为「Nacos 覆盖本地」—— 反例：Nacos 里写着
`spring.datasource.druid.initial-size: 5`，而本地 yml 里根本没有这个键，
运行时 `DruidDataSource.getInitialSize()` 仍是 **0**（证据：`DataSourceKeepAliveHeartbeat`
启动日志「同时热 **1** 条连接」，而它算的是 `max(1, initialSize)`）。
因此本文档与 `AGENTS.md` 里原先那句「Nacos 上的同名键会覆盖本地 yml，改本地是白改」
**已被推翻**：目前能确认的是「不再报错」，而「Nacos 的值是否真的进入 Environment」仍是**未证实**，
并且已有一个反例。结论按实际口径写：**要调参数就两处都改**，别赌哪一份生效。

> 附带教训：`optional:` 导入把配置中心故障降级成一行日志，方向是对的（配置中心挂了不该拖垮服务），
> 但**必须有人看这行日志** —— 排查时先 `Select-String 'Error getting properties from nacos'`，
> 别只看服务起没起来。

## Redis 基础设施
- `common-core` 通过 Spring Data Redis 提供阻塞式 `RedisUtils` 与 `RedisCache`，供 Servlet 业务服务注入；网关单独使用 Reactive Redis 检查 JWT 撤销列表，禁止在 Netty 事件循环里调用阻塞式工具。撤销键规则由 `shared-model` 共享。
- 键使用字符串，普通值统一以 JSON 存储；支持带 TTL 写入、类型化读取、删除、存在判断、修改 TTL、原子整数计数和故障回源的旁路缓存。
- user-service 使用 Redis 维护登录失败窗口、账号锁定和 JWT 撤销记录，并缓存公开作者摘要（5 分钟）；完整用户资料含登录名和申请理由，不进入共享缓存。
- content-service 缓存公开文章/笔记列表与详情、标签、统计、评论、友链、流星和回声；TTL 按数据热度为 30 秒、1 分钟或 5 分钟。参数化列表使用“命名空间版本 + 参数指纹”构造键，内容写入后原子推进版本，使旧参数组合立即不可达。
- 草稿、私有笔记、我的内容、复核队列、完整用户资料、用户列表和友链待审队列不进入共享缓存；不引入 Redis Session。
- 浏览去重、点赞明细及文章/笔记计数仍以 MySQL 为事实来源。登录用户的每日闸门用条件更新 + `INSERT IGNORE` 原子抢占，并与计数更新处于同一事务；浏览与点赞成功后只清理详情缓存，列表计数允许在短 TTL 内最终一致。
- 普通缓存采用故障放行：Redis 不可用时回源 MySQL，并短暂熔断 30 秒；登录锁定和令牌撤销是安全状态，不故障放行。尚未实现 Redis 限流或分布式锁。
- 连接参数统一来自 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE`；命令超时 dev 500ms / prod 1s，连接超时 2s（缓存超时到点即回源；网关撤销校验 fail-closed，缩短超时只是更快暴露 503，不放行）。
- **Lettuce 三处加固**（远端 Redis 必配）：`common-core` 的 `RedisLettuceTuningConfig` 关掉共享原生连接（一条坏连接不再拖垮所有命令）、把池里的空闲连接压到一条；`RedisKeepAliveHeartbeat` 每 30s 借一条连接发**真 PING** —— 这一条才是治「一段时间不操作就 503」的关键：本机与远端 Redis 之间空闲十几分钟的连接会被 NAT/防火墙**静默丢弃**（本机还是 ESTABLISHED、Redis 那边已无此连接，实测 11 条对 6 条，且服务端 `CONFIG GET timeout`=0），之后第一个命令就是写进黑洞。⚠️ 连接池的 `testWhileIdle`/`testOnBorrow` 在这里**没用**：Lettuce 的池化工厂只做 `StatefulConnection.isOpen()`（本地标志位），不会发网络包。⚠️ **心跳必须打在实际被用的那个池上**：Spring Data Redis 的阻塞路径（`getConnection` → commons-pool2 的 `pools`）与响应式路径（`getConnectionAsync` → Lettuce 的 `asyncPools`）是**两个独立的池**，网关的撤销校验走响应式，因此网关那份心跳用 `getReactiveConnection().ping()`，common-core 那份（user/content 用 `StringRedisTemplate`）用阻塞 API。`commons-pool2` 是池生效的前提；心跳开关 `stellar.ink.redis.keepalive.enabled`（ai-service 显式关掉）。
- Actuator 会自动加入 Redis 健康项；Redis 不可达时三个 Java 服务的 `/actuator/health` 为 `DOWN`。

## 配置与部署

每个运行服务保留 `application.yml`、`application-dev.yml`、`application-prod.yml`、`nacos-application-dev.yml` 和 `logback-spring.xml`。Nacos Data ID 分别为：

- `gateway-nacos-sentinel-dev.yaml`
- `user-service-dev.yaml`
- `content-service-dev.yaml`

生产环境由 [docker-compose.yml](../../deploy/docker/docker-compose.yml) 编排网关、两个业务服务和前端 Nginx。MySQL、Redis 与 Nacos 继续复用宿主机现有实例。

前端 history 路由 `/notes`、`/links`、`/search` 与后端 API 前缀重名。Vite 与生产 Nginx 通过
`GET + Accept: text/html` 识别浏览器页面导航并回退 `index.html`；`fetch` 的 `Accept: */*`
继续代理到网关。新增重名路由时必须保持这条分流规则，不能简单按路径把所有请求都代理到后端。

## 再拆分门槛

只有某个领域出现以下情况之一时再拆成独立服务：需要独立扩缩容；需要独立数据库或事务边界；需要不同发布节奏；存在明确团队所有权；故障隔离收益显著高于远程调用成本。不要只因表不同就拆服务。
