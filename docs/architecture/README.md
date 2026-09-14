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
                         ┌────────────┴────────────┐
                         ▼                         ▼
                  user-service :8101       content-service :8102
                  用户、认证、角色          post / note / meteor
                         │                  echo / link / stats
                         └────────────┬────────────┘
                                      ▼
                         MySQL（共享 stellar_ink 库）

  三个 Java 服务均注册到 Nacos :8848（注册中心 + 配置中心）
```

## 模块结构

```text
stellar-ink-server/
├── pom.xml
├── common-components/
│   ├── shared-model/          Response、异常、跨模块 DTO/VO
│   └── common-core/           异常处理、TraceId、MyBatis-Plus、鉴权辅助
├── gateway-nacos-sentinel/    API 网关（8080）
├── user-service/              用户与认证（8101）
└── content-service/           内容聚合服务（8102）
    └── com.stellarink.content/
        ├── post/              文章、标签、搜索
        ├── note/              技术笔记（结构化 / 可私有 / 可验证）
        ├── meteor/            流星备忘录
        ├── echo/              回声漂流瓶
        ├── link/              星链友链
        └── stats/             写作脉搏
```

> `post`（星/文章）与 `note`（标本/技术笔记）是两张独立表：文章重文笔、天然公开；
> 笔记结构化、**可私有**（`visibility`）、会过期（`verified_at`）。两者边界不同，因此不合并为一张表。

AI 技术路线和分阶段实现方案见 [docs/ai/README.md](../ai/README.md)。当前 AI 目录仍处于方案阶段，未纳入后端 Maven 模块和 Docker 编排。

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

网关关闭 discovery locator，只允许显式路由，防止通过 `/{serviceId}/**` 绕过鉴权。`/internal/**` 不对外路由。
`/uploads/**` 是 `user-uploads` 路由（指向 user-service 的静态资源映射），用于头像等上传文件的**匿名读**；
上传/删除本身走 `/user/avatar`，受网关鉴权保护。

## 鉴权

- user-service 负责登录、注册和 JWT 签发。
- 网关按路径和角色进行第一层校验；业务服务使用 `AuthHelper` 复核登录身份与角色。
- `READER` 可读和公开互动，`AUTHOR` 可维护自己的文章与流星，`ADMIN` 可管理全部内容、友链状态和用户角色。
- JWT 无状态验签，网关与两个业务服务必须使用同一 `SA_TOKEN_JWT_SECRET`。
- **笔记的私有隔离不在网关**：网关对所有 GET 放行，`PRIVATE` 笔记的可读性由 content-service 的
  `NoteServiceImpl#ensureReadable` 判定（非作者一律 404，ADMIN 也不能读他人私有笔记）；
  归属判定 `ensureOwned` 比文章更严格，只有作者本人能改删。

## 数据边界

| 服务 | 负责的数据 |
|---|---|
| user-service | `user` 表；头像图片文件（本地磁盘 `UPLOAD_DIR`，生产由 Docker 卷持久化） |
| content-service | `post`、`post_glow`、`note` 表，`meteor`、`echo`、`link`，以及基于 `post` 的实时统计 |
| 跨内容类型共用 | `post_view`（浏览计数闸门：只记「某用户某天已计一次」，与内容类型无关，文章与笔记共用） |

> 头像存储有**两种实现**（`stellar.ink.storage.type` 切换）：
> `local` 本地磁盘（文件与库必须同机可达，多实例或本地连远程库会出现「上传成功但图片 404」）；
> `cos` 腾讯云对象存储（推荐生产，图片与数据库解耦）。
> 方案、部署形态、迁移与回滚见 [avatar-minio.md](avatar-minio.md)。

当前使用一个 `stellar_ink` 数据库。服务之间不直接访问对方负责的表，也没有同步服务调用。统计逻辑与文章同进程，直接通过 `PostMapper` 查询已发布文章。

## 配置与部署

每个运行服务保留 `application.yml`、`application-dev.yml`、`application-prod.yml`、`nacos-application-dev.yml` 和 `logback-spring.xml`。Nacos Data ID 分别为：

- `gateway-nacos-sentinel-dev.yaml`
- `user-service-dev.yaml`
- `content-service-dev.yaml`

生产环境由 [docker-compose.yml](../../deploy/docker/docker-compose.yml) 编排网关、两个业务服务和前端 Nginx。MySQL 与 Nacos 继续复用宿主机现有实例。

## 再拆分门槛

只有某个领域出现以下情况之一时再拆成独立服务：需要独立扩缩容；需要独立数据库或事务边界；需要不同发布节奏；存在明确团队所有权；故障隔离收益显著高于远程调用成本。不要只因表不同就拆服务。
