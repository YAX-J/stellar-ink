# 星笺后端接口文档（Spring Cloud Alibaba 微服务 v2）

> v2 起后端为微服务架构（详见 [docs/architecture/README.md](../architecture/README.md)）。
> 对外唯一入口为 **网关 :8080**，API 路径与 v1 完全一致，前端无感。
> 统一响应 `{ "code": 0, "message": "ok", "data": ... }`；4xx/5xx 同步 HTTP 状态码。

## 版本矩阵

Spring Boot 3.3.12 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.3（官方匹配组合）+ MyBatis-Plus 3.5.12。

## 运行

```bash
cd stellar-ink-server && mvn -DskipTests package
```

一键启动（Nacos + 6 服务 + 网关）：`deploy\scripts\start-all.bat`
或手动按顺序：

```bash
# 1. Nacos（standalone，首次需从 GitHub release 下载到 tools/nacos）
tools\nacos\bin\startup.cmd -m standalone          # 控制台 http://localhost:8848/nacos
# 2. 业务服务（各自 jar，见模块 stellar-ink-service-*）
# 3. 网关
java -jar stellar-ink-gateway/target/stellar-ink-gateway.jar
```

环境变量：`NACOS_ADDR`（默认 localhost:8848）、`MYSQL_HOST/PORT/DB/USER/PASSWORD`、`STELLAR_JWT_SECRET`。
种子账号：`stellar / stellar123`。

## 服务与端口

| 服务 | 端口 | 路由前缀 | 表 |
|---|---|---|---|
| gateway-service | 8080 | 对外唯一入口 | - |
| user-service | 8101 | `/auth/**` `/user/**` | user |
| post-service | 8102 | `/posts/**` `/tags/**` `/search/**` | post |
| meteor-service | 8103 | `/meteors/**` | meteor |
| echo-service | 8104 | `/echos/**` | echo |
| link-service | 8105 | `/links/**` | link |
| stats-service | 8106 | `/stats/**` | -（Feign 聚合） |

## 鉴权（网关统一）

- 放行：GET/OPTIONS、`/auth/**`、公开写接口（`POST /echos`、`POST /links`、`POST /posts/{id}/glow`）
- 需 `Authorization: Bearer <token>`：对 `/posts/**`、`/meteors/**`、`/links/**`、`/user/**` 的写请求
- 网关校验后注入 `X-User-Id` 转发；下游服务不接触 JWT

## 接口一览（经网关调用）

### user-service

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/login` | 登录，返回 `{token, user}` | 公开 |
| GET | `/user/profile` | 站长资料 | 登录 |
| PUT | `/user/profile` | 更新资料 `{nickname?, signature?, avatarText?, dailyGoal?}` | 登录 |

### post-service

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/posts` | 分页列表；`page/size/year/tag/keyword/status` | 公开 |
| GET | `/posts/{id}` | 详情（正文、阅读时长、prev/next 相邻星） | 公开 |
| POST | `/posts` | 发射 `{title, content, tags[], status}` | 登录 |
| PUT / DELETE | `/posts/{id}` | 更新 / 删除 | 登录 |
| POST | `/posts/{id}/glow` | 补充光芒，返回 `{glow}` | 公开 |
| GET | `/tags` | 标签计数（光谱） | 公开 |
| GET | `/search?keyword=` | 标题/正文搜索 | 公开 |
| GET | `/internal/posts/summary` | 服务间内部汇总（网关不路由，仅 stats 调用） | 内部 |

### meteor-service

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/meteors?limit=50` | 最近流星 | 公开 |
| POST | `/meteors` | 发射 `{content}` | 登录 |
| DELETE | `/meteors/{id}` | 删除 | 登录 |

### echo-service

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/echos` | 全部漂流瓶 | 公开 |
| POST | `/echos` | 投瓶 `{nickname?, content}` | 公开 |

### link-service

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/links` | 友邻列表 | 公开 |
| POST | `/links` | 申请接入 `{name, url, description?}` | 公开 |
| PUT | `/links/{id}/status?status=1` | 站长确认/驳回 | 登录 |

### stats-service

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/stats/overview` | 写作脉搏：totalPosts / totalWords / todayWords / streakDays / nightRatio / tagDistribution | 公开 |

## 数据库

共享库模式：一个 `stellar_ink` 库，各服务只读写自己的表（兼容云数据库无建库权限场景）。
- dev：各服务内置 H2（`schema.sql` 含幂等种子，与前端 prototype mock 对齐）
- 生产：`deploy/sql/01_schema.sql` + `02_init-data.sql`（已在 MySQL 8.0.45 实测）
- 拆库：每个服务独立 `MYSQL_DB` 环境变量即可，无需改代码

## 日志

同 v1：SLF4J，各服务独立文件 `logs/stellar-ink-<服务>.log`（UTF-8，按天+20MB 滚动，留 14 天）；
访问日志 `API-ACCESS`；登录失败/未授权/业务异常 WARN。

## 已知边界（后续迭代）

- 服务间调用暂无熔断（可加 Sentinel）；stats 为实时 Feign 聚合（可改 MQ/物化视图）
- 搜索为 LIKE；未做注册/多用户/评论/文件上传
- Nacos 当前仅做注册中心，配置中心（nacos-config）按需引入
