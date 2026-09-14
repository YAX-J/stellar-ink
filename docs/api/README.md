# 星笺后端接口文档（Spring Cloud Alibaba 微服务）

> 对外唯一入口为 **网关 :8080**，API 路径与早期版本一致，前端无感。
> 统一响应 `{ "code": 0, "msg": "成功", "data": ..., "traceId": "..." }`；业务错误 code 非 0（HTTP 200），网关鉴权失败 code=401。
> 版本矩阵与架构详见 [docs/architecture/README.md](../architecture/README.md)。

## 运行

```bash
cd stellar-ink-server && mvn -DskipTests package
deploy\scripts\start-all.bat        # 一键：user/content 两个业务服务 + 网关
```

环境变量：`NACOS_ADDR`（默认 127.0.0.1:8848）、`MYSQL_HOST/PORT/DB/USER/PASSWORD`、`SA_TOKEN_JWT_SECRET`。
种子账号：`stellar / stellar123`。

## 服务与端口

| 服务 | 端口 | 路由前缀 | 表 |
|---|---|---|---|
| gateway-nacos-sentinel | 8080 | 对外唯一入口 | - |
| user-service | 8101 | `/auth/**` `/user/**` | user |
| content-service | 8102 | `/posts/**` `/tags/**` `/search/**` `/meteors/**` `/echos/**` `/links/**` `/stats/**` | post / meteor / echo / link |

## 鉴权（Sa-Token，网关统一）

- 登录返回 `tokenName: Authorization` 与 `tokenValue`；后续请求带 `Authorization: <tokenValue>`（无 Bearer 前缀）
- 放行：GET/OPTIONS、`POST /auth/login`、`POST /auth/register`、公开写接口（`POST /echos`、`POST /links`、`POST /posts/{id}/glow`、`POST /posts/{id}/viewed`）
- 其余写请求需有效 token，失败返回 `{"code":401,...}`（网关同时设置真实 HTTP 状态 401/403）
- **鉴权失败返回形态不一致，前端必须 code/status 联合判断**：网关层拦截是 HTTP 401/403；而 GET 请求在网关是放行的，token 失效时由服务端 `NotLoginException` 兜底，返回的是 **HTTP 200 + `code:401`**

### 角色模型（三档，权限向下累积）

| 角色 | 中文 | 能力 |
|---|---|---|
| READER | 读者 | 读 + 公开互动（点赞/投瓶/申请友链），不可创作 |
| AUTHOR | 作者 | READER 全部 + 写/改/删文章、发射/删除流星 |
| ADMIN | 站长 | AUTHOR 全部 + 友链审核 + 调整用户角色 |

- 角色在登录/注册时写入 JWT；注册固定 `READER`，种子账号 `stellar` 为 `ADMIN`
- 网关按角色做门槛：文章/流星写操作需 `AUTHOR`；`PUT /links/{id}/status`、`PUT /user/{id}/role`、`GET /user/list` 需 `ADMIN`
- 角色不足返回 `{"code":403,...}`；文章与流星按 `user_id` 记录作者归属，AUTHOR 只能维护自己的内容，ADMIN 可管理全部内容

## 接口一览（经网关调用）

### user-service :8101

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/register` | 注册（开放），注册即登录，返回 `{tokenName, tokenValue, user}` | 公开 |
| POST | `/auth/login` | 登录，返回 `{tokenName, tokenValue, user}` | 公开 |
| POST | `/auth/logout` | 登出（无状态 JWT 语义收口，前端丢 token） | 登录 |
| GET | `/user/authors?ids=1,2` | 批量查询公开作者摘要（最多 100 个，仅返回 id/笔名/头像底字） | 公开 |
| GET | `/user/profile` | 当前用户资料 | 登录 |
| PUT | `/user/profile` | 更新资料 `{nickname?, signature?, avatarText?, dailyGoal?}` | 登录 |
| PUT | `/user/password` | 修改密码 `{oldPassword, newPassword}` | 登录 |
| PUT | `/user/{id}/role` | 调整角色 `{role: READER/AUTHOR/ADMIN}`，返回更新后的 user | ADMIN |
| GET | `/user/list` | 用户列表（供角色管理页枚举） | ADMIN |

### content-service :8102 - 文章

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/posts` | 已发布文章分页列表；`page/size/year/tag/keyword/orderBy` | 公开 |
| GET | `/posts/mine` | 当前作者自己的草稿列表 | AUTHOR |
| GET | `/posts/{id}` | 详情（已发布文章公开；草稿仅作者本人或 ADMIN），含 `viewCount` 与当前用户 `liked` | 按状态 |
| POST | `/posts` | 发射 `{title, content, tags[], status}` | AUTHOR |
| PUT / DELETE | `/posts/{id}` | 更新 / 删除；AUTHOR 仅自己的文章，ADMIN 可操作全部 | AUTHOR |
| POST | `/posts/{id}/glow` | 补充光芒，返回 `{glow, liked, applied}` | 公开 |
| POST | `/posts/{id}/viewed` | 记录一次浏览，返回 `{counted}` | 公开 |
| GET | `/tags` | 标签计数（光谱） | 公开 |
| GET | `/search?keyword=` | 标题/正文搜索 | 公开 |

- `orderBy` 取值：`latest` 最新（默认）/ `hottest` 最受回望（按 `glow`）/ `longest` 篇幅最长；一律追加 `id` 倒序保证分页稳定
- `tag` 为 **`LIKE '%tag%'` 子串匹配**（逗号串），不是精确标签匹配 —— 前端拿到结果后需按 `tags.includes(tag)` 二次过滤
- 分页 `size` 服务端硬上限 100（超过会被静默夹到 100），公开列表总条数从 `IPage.total` 取
- 浏览量口径：登录用户在 `post_view` 闸门表里按天去重（同一人同一天多次刷新只计一次），未登录访客每次计数
- 点赞口径：登录用户一人一赞（`post_glow` 唯一键 `(post_id, user_id)`，重复点击 `applied=false` 且不重复计数）；未登录访客一次点击一次计数，`liked` 恒为 `false`

### content-service :8102 - 流星

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/meteors?limit=50` | 最近流星 | 公开 |
| POST | `/meteors` | 发射 `{content}` | AUTHOR |
| DELETE | `/meteors/{id}` | 删除；AUTHOR 仅自己的流星，ADMIN 可操作全部 | AUTHOR |

### content-service :8102 - 回声

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/echos` | 全部漂流瓶 | 公开 |
| POST | `/echos` | 投瓶 `{nickname?, content}` | 公开 |

### content-service :8102 - 星链

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/links` | 友邻列表 | 公开 |
| POST | `/links` | 申请接入 `{name, url, description?}` | 公开 |
| PUT | `/links/{id}/status?status=1` | 站长确认/驳回 | ADMIN |

### content-service :8102 - 写作统计

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/stats/overview` | 写作脉搏：totalPosts / totalWords / todayWords / streakDays / nightRatio / tagDistribution（**全站统计，不区分用户**） | 公开 |

### 各服务通用

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 简单健康检查（common-core 提供） |
| GET | `/actuator/health` | Spring Boot 健康端点 |

## 数据库

共享库模式：一个 `stellar_ink` 库；user-service 负责 `user` 表，content-service 负责内容领域各表（Druid 连接池，dev 直连本机 MySQL）。
初始化：`deploy/sql/01_schema.sql` + `02_init-data.sql`（幂等）。
已有数据库升级脚本（按需各执行一次）：`03_multi-author.sql`（多作者归属）、`04_post_views_glow.sql`（浏览量 `post.view_count` + 点赞明细 `post_glow` + 浏览闸门 `post_view`）。拆库：改各服务 `MYSQL_DB` 环境变量。

> `04_post_views_glow.sql` 最后一段会用 `post_glow` 明细重算 `post.glow`，升级前的历史点赞没有 user_id 明细，重算后会计数归零——需要保留旧计数时跳过该段。

## 日志

每服务独立 `logback-spring.xml`：控制台 + 异步文件 `logs/<app>.log`（UTF-8，按天 + 200MB 滚动，30 天）；
dev 环境控制台打印 SQL（mybatis-plus log-impl）。

## 已知边界（后续迭代）

- Sentinel 规则未持久化（sentinel-datasource-nacos 已引入，待配规则）
- 未启用 Redis 令牌桶限流（需 Redis）；生产已有 Nginx 边缘限流（详见 `deploy/docker/nginx/default.conf`，注意 `^/(echos|links)$` 不分方法限流，`GET` 也在限流区内、超限 429）
- 搜索为 LIKE；已做开放注册（`POST /auth/register`，注册即 READER）
- 未做评论/文件上传/多租户数据隔离；`echo`/`link` 仍无 `user_id`（友链为全局数据）
- 点赞不支持取消（只有「已赞」状态，没有取消接口）；浏览量匿名每次计数，无 IP 维度去重
- `GET /health` 经网关不可达（网关无 common-core 依赖、路由未声明），只能直连 :8101/:8102
