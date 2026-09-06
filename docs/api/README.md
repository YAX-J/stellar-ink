# 星笺后端接口文档（基础功能 v1）

服务端口 `8080`，统一响应结构 `{ "code": 0, "message": "ok", "data": ... }`，非 0 为失败。
鉴权：登录后携带请求头 `Authorization: Bearer <token>`；**读接口全部公开**，写接口（文章/流星/友链状态/资料更新）需登录；回声投瓶与友链申请保持公开。

## 运行

```bash
# 开发（默认 dev profile，H2 内存库 + 种子数据，无需装数据库）
mvn -DskipTests package
java -jar stellar-ink-api/target/stellar-ink-api.jar

# 生产（MySQL 8，先手动执行 stellar-ink-api/src/main/resources/schema.sql 建表）
java -jar stellar-ink-api/target/stellar-ink-api.jar --spring.profiles.active=mysql
```

种子账号：`stellar / stellar123`（种子数据与前端 prototype 的 mock 内容对齐）。

## 接口一览

### 认证 AuthController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/login` | 登录，body `{username, password}`，返回 `{token, user}` | 公开 |

### 文章 PostController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/posts` | 分页列表；参数 `page/size/year/tag/keyword/status` | 公开 |
| GET | `/posts/{id}` | 详情（含正文、阅读时长、prev/next 相邻星） | 公开 |
| POST | `/posts` | 发射新文章，body `{title, content, tags[], status}` | 登录 |
| PUT | `/posts/{id}` | 更新文章 | 登录 |
| DELETE | `/posts/{id}` | 删除文章 | 登录 |
| POST | `/posts/{id}/glow` | 为这颗星补充光芒，返回 `{glow}` | 公开 |

- `tags` 服务端以逗号分隔存储；`word_count` 由正文去空白字符后统计
- `status`：0 草稿 / 1 已发布（星图与公开列表只展示已发布）

### 标签 TagController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/tags` | 标签及文章计数（光谱页），按计数降序 | 公开 |

### 流星 MeteorController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/meteors?limit=50` | 最近流星列表 | 公开 |
| POST | `/meteors` | 发射流星，body `{content}` | 登录 |
| DELETE | `/meteors/{id}` | 删除流星 | 登录 |

### 回声 EchoController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/echos` | 全部漂流瓶（新→旧） | 公开 |
| POST | `/echos` | 投瓶入海，body `{nickname?, content}`，缺省昵称「匿名旅人」 | 公开 |

### 星链 LinkController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/links` | 友邻列表 | 公开 |
| POST | `/links` | 申请接入，body `{name, url, description?}`，默认待确认 | 公开 |
| PUT | `/links/{id}/status?status=1` | 站长确认（1）/驳回（0） | 登录 |

### 统计 StatsController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/stats/overview` | 写作脉搏：`totalPosts / totalWords / todayWords / streakDays / nightRatio / tagDistribution` | 公开 |

### 搜索 SearchController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/search?keyword=缓缓` | 标题/正文关键字搜索（LIKE） | 公开 |

### 用户 UserController

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/user/profile` | 当前用户资料（笔名/签名/头像字/每日目标） | 登录 |
| PUT | `/user/profile` | 更新资料，body `{nickname?, signature?, avatarText?, dailyGoal?}` | 登录 |

## 数据库

5 张表：`post`、`meteor`、`echo`、`link`、`user`（身份舱/星籍资料并入 user 表）。
DDL 见 `stellar-ink-api/src/main/resources/schema.sql`（H2 MySQL 模式与 MySQL 8 均可执行）；种子数据 `data.sql` 仅 dev 环境自动执行。

## 模块结构

```
stellar-ink-server
├── stellar-ink-common   Result/ResultCode、BusinessException、JwtUtil（零 Spring 依赖）
├── stellar-ink-domain   DTO / VO / 枚举
├── stellar-ink-dao      MyBatis-Plus 实体与 Mapper
├── stellar-ink-service  post/tag/meteor/echo/link/stats/search/user
└── stellar-ink-api      启动类、Controller、AuthInterceptor、全局异常、CORS
（stellar-ink-ai-client 预留目录，AI 功能后续接入）
```

## 已知边界（后续迭代）

- 搜索为 LIKE 匹配，数据量大后可换全文索引
- 标签过滤用 LIKE，标签含子串关系时可能多匹配（当前标签集无此问题）
- 未接注册/多用户，单站长模式；密码 BCrypt 存储
- 时间字段序列化为 ISO-8601（`LocalDateTime`），前端负责展示格式化（如「今天 01:12」）
