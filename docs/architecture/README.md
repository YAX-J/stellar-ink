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
                    └────────────┬────────────┘              │ HMAC 内网签名（M1）
                          ┌──────┴──────┐                     ▼
                          ▼             ▼            stellar-ink-ai :8200
              MySQL（共享 stellar_ink） Redis          Python：模型网关 / RAG / Agent
                                                     （仅内网可达，浏览器不直连）

  四个 Java 服务均注册到 Nacos :8848（注册中心 + 配置中心）；
  Python 不注册 Nacos，地址由 ai-service 的 python-base-url 固定配置
```

> **AI 当前进度（M0 已完成）**：`ai-service :8107` 与 Python `stellar-ink-ai :8200` 的
> 工程骨架、跨语言契约与 `/ai/health` 已落地，全程 Fake Adapter、无任何密钥。
> 网关的 `/ai/**` 路由、HMAC 签名与真实调用属 **M1**；M0 不对外暴露 AI 能力，
> 因此上面拓扑里的 `/ai/**` 路由与 HMAC 已按目标态画出但尚未接线。

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
| ai-service | `/ai/**`（M1 接入；M0 只有直连 8107 的 `/ai/health`） |

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

## Redis 基础设施

- `common-core` 通过 Spring Data Redis 提供阻塞式 `RedisUtils` 与 `RedisCache`，供 Servlet 业务服务注入；网关单独使用 Reactive Redis 检查 JWT 撤销列表，禁止在 Netty 事件循环里调用阻塞式工具。撤销键规则由 `shared-model` 共享。
- 键使用字符串，普通值统一以 JSON 存储；支持带 TTL 写入、类型化读取、删除、存在判断、修改 TTL、原子整数计数和故障回源的旁路缓存。
- user-service 使用 Redis 维护登录失败窗口、账号锁定和 JWT 撤销记录，并缓存公开作者摘要（5 分钟）；完整用户资料含登录名和申请理由，不进入共享缓存。
- content-service 缓存公开文章/笔记列表与详情、标签、统计、评论、友链、流星和回声；TTL 按数据热度为 30 秒、1 分钟或 5 分钟。参数化列表使用“命名空间版本 + 参数指纹”构造键，内容写入后原子推进版本，使旧参数组合立即不可达。
- 草稿、私有笔记、我的内容、复核队列、完整用户资料、用户列表和友链待审队列不进入共享缓存；不引入 Redis Session。
- 浏览去重、点赞明细及文章/笔记计数仍以 MySQL 为事实来源。登录用户的每日闸门用条件更新 + `INSERT IGNORE` 原子抢占，并与计数更新处于同一事务；浏览与点赞成功后只清理详情缓存，列表计数允许在短 TTL 内最终一致。
- 普通缓存采用故障放行：Redis 不可用时回源 MySQL，并短暂熔断 30 秒；登录锁定和令牌撤销是安全状态，不故障放行。尚未实现 Redis 限流或分布式锁。
- 连接参数统一来自 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE`；命令超时 dev 500ms / prod 1s，连接超时 2s（缓存超时到点即回源；网关撤销校验 fail-closed，缩短超时只是更快暴露 503，不放行）。
- **Lettuce 两处加固**（远端 Redis 必配）：`common-core` 的 `RedisLettuceTuningConfig` 关掉共享原生连接（一条坏连接不再拖垮所有命令），并让连接池验活空闲连接（`testWhileIdle`，跨公网连接被 NAT 掐断后不再让 idle 后的第一个命令等到超时）；网关是 WebFlux，自带一份等价实现 `RedisConnectionTuningConfig`，改动要同步两处。`commons-pool2` 是池生效的前提。
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
