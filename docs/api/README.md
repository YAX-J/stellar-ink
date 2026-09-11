# 星笺后端接口文档（Spring Cloud Alibaba 微服务）

> 对外唯一入口为 **网关 :8080**，API 路径与早期版本一致，前端无感。
> 统一响应 `{ "code": 0, "msg": "成功", "data": ..., "traceId": "..." }`；业务错误 code 非 0（HTTP 200），网关鉴权失败 code=401。
> 版本矩阵与架构详见 [docs/architecture/README.md](../architecture/README.md)。

## 运行

```bash
cd stellar-ink-server && mvn -DskipTests package
deploy\scripts\start-all.bat        # 一键：Nacos + 6 服务 + 网关
```

环境变量：`NACOS_ADDR`（默认 127.0.0.1:8848）、`MYSQL_HOST/PORT/DB/USER/PASSWORD`、`SA_TOKEN_JWT_SECRET`。
种子账号：`stellar / stellar123`。

## 服务与端口

| 服务 | 端口 | 路由前缀 | 表 |
|---|---|---|---|
| gateway-nacos-sentinel | 8080 | 对外唯一入口 | - |
| user-service | 8101 | `/auth/**` `/user/**` | user |
| post-service | 8102 | `/posts/**` `/tags/**` `/search/**` | post |
| meteor-service | 8103 | `/meteors/**` | meteor |
| echo-service | 8104 | `/echos/**` | echo |
| link-service | 8105 | `/links/**` | link |
| stats-service | 8106 | `/stats/**` | -（Feign 聚合） |

## 鉴权（Sa-Token，网关统一）

- 登录返回 `tokenName: Authorization` 与 `tokenValue`；后续请求带 `Authorization: <tokenValue>`（无 Bearer 前缀）
- 放行：GET/OPTIONS、`POST /auth/login`、`POST /auth/register`、公开写接口（`POST /echos`、`POST /links`、`POST /posts/{id}/glow`）
- 其余写请求需有效 token，失败返回 `{"code":401,...}`

### 角色模型（三档，权限向下累积）

| 角色 | 中文 | 能力 |
|---|---|---|
| READER | 读者 | 读 + 公开互动（点赞/投瓶/申请友链），不可创作 |
| AUTHOR | 作者 | READER 全部 + 写/改/删文章、发射/删除流星 |
| ADMIN | 站长 | AUTHOR 全部 + 友链审核 + 调整用户角色 |

- 角色在登录/注册时写入 JWT；注册固定 `READER`，种子账号 `stellar` 为 `ADMIN`
- 网关按角色做门槛：文章/流星写操作需 `AUTHOR`；`PUT /links/{id}/status`、`PUT /user/{id}/role`、`GET /user/list` 需 `ADMIN`
- 角色不足返回 `{"code":403,...}`；角色不参与数据归属（文章/流星不区分作者）

## 接口一览（经网关调用）

### user-service :8101

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/register` | 注册（开放），注册即登录，返回 `{tokenName, tokenValue, user}` | 公开 |
| POST | `/auth/login` | 登录，返回 `{tokenName, tokenValue, user}` | 公开 |
| POST | `/auth/logout` | 登出（无状态 JWT 语义收口，前端丢 token） | 登录 |
| GET | `/user/profile` | 站长资料 | 登录 |
| PUT | `/user/profile` | 更新资料 `{nickname?, signature?, avatarText?, dailyGoal?}` | 登录 |
| PUT | `/user/password` | 修改密码 `{oldPassword, newPassword}` | 登录 |
| PUT | `/user/{id}/role` | 调整角色 `{role: READER/AUTHOR/ADMIN}`，返回更新后的 user | ADMIN |
| GET | `/user/list` | 用户列表（供角色管理页枚举） | ADMIN |

### post-service :8102

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/posts` | 分页列表；`page/size/year/tag/keyword/status` | 公开 |
| GET | `/posts/{id}` | 详情（正文、阅读时长、prev/next 相邻星） | 公开 |
| POST | `/posts` | 发射 `{title, content, tags[], status}` | AUTHOR |
| PUT / DELETE | `/posts/{id}` | 更新 / 删除 | AUTHOR |
| POST | `/posts/{id}/glow` | 补充光芒，返回 `{glow}` | 公开 |
| GET | `/tags` | 标签计数（光谱） | 公开 |
| GET | `/search?keyword=` | 标题/正文搜索 | 公开 |
| GET | `/internal/posts/summary` | 服务间内部汇总（网关不路由，仅 stats 调用） | 内部 |

### meteor-service :8103

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/meteors?limit=50` | 最近流星 | 公开 |
| POST | `/meteors` | 发射 `{content}` | AUTHOR |
| DELETE | `/meteors/{id}` | 删除 | AUTHOR |

### echo-service :8104

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/echos` | 全部漂流瓶 | 公开 |
| POST | `/echos` | 投瓶 `{nickname?, content}` | 公开 |

### link-service :8105

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/links` | 友邻列表 | 公开 |
| POST | `/links` | 申请接入 `{name, url, description?}` | 公开 |
| PUT | `/links/{id}/status?status=1` | 站长确认/驳回 | ADMIN |

### stats-service :8106

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/stats/overview` | 写作脉搏：totalPosts / totalWords / todayWords / streakDays / nightRatio / tagDistribution | 公开 |

### 各服务通用

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 简单健康检查（common-core 提供） |
| GET | `/actuator/health` | Spring Boot 健康端点 |

## 数据库

共享库模式：一个 `stellar_ink` 库，各服务只读写自己的表（Druid 连接池，dev 直连本机 MySQL）。
初始化：`deploy/sql/01_schema.sql` + `02_init-data.sql`（幂等）。拆库：改各服务 `MYSQL_DB` 环境变量。

## 日志

每服务独立 `logback-spring.xml`：控制台 + 异步文件 `logs/<app>.log`（UTF-8，按天 + 200MB 滚动，30 天）；
dev 环境控制台打印 SQL（mybatis-plus log-impl）。

## 已知边界（后续迭代）

- Sentinel 规则未持久化（sentinel-datasource-nacos 已引入，待配规则）
- 未启用 Redis 令牌桶限流（需 Redis）
- 搜索为 LIKE；已做开放注册（`POST /auth/register`，注册即 READER）；未做评论/文件上传/多租户数据隔离
