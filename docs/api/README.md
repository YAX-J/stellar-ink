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
| user-service | 8101 | `/auth/**` `/user/**` `/uploads/**` | user |
| content-service | 8102 | `/posts/**` `/notes/**` `/tags/**` `/search/**` `/meteors/**` `/echos/**` `/links/**` `/stats/**` | post / note / meteor / echo / link |

## 鉴权（Sa-Token，网关统一）

- 登录返回 `tokenName: Authorization` 与 `tokenValue`；后续请求带 `Authorization: <tokenValue>`（无 Bearer 前缀）
- 放行：GET/OPTIONS、`POST /auth/login`、`POST /auth/register`、公开写接口（`POST /echos`、`POST /links`、`POST /posts/{id}/glow`、`POST /posts/{id}/viewed`、`POST /notes/{id}/viewed`）
- 其余写请求需有效 token，失败返回 `{"code":401,...}`（网关同时设置真实 HTTP 状态 401/403）
- **鉴权失败返回形态不一致，前端必须 code/status 联合判断**：网关层拦截是 HTTP 401/403；而 GET 请求在网关是放行的，token 失效时由服务端 `NotLoginException` 兜底，返回的是 **HTTP 200 + `code:401`**

### 角色模型（三档，权限向下累积）

| 角色 | 中文 | 能力 |
|---|---|---|
| READER | 读者 | 读 + 公开互动（点赞/投瓶/申请友链），不可创作 |
| AUTHOR | 作者 | READER 全部 + 写/改/删文章、技术笔记、发射/删除流星 |
| ADMIN | 站长 | AUTHOR 全部 + 友链审核 + 调整用户角色 |

- 角色在登录/注册时写入 JWT；注册固定 `READER`，种子账号 `stellar` 为 `ADMIN`
- 网关按角色做门槛：文章/笔记/流星写操作需 `AUTHOR`；`PUT /links/{id}/status`、`PUT /user/{id}/role`、`GET /user/list` 需 `ADMIN`
- 角色不足返回 `{"code":403,...}`；文章与流星按 `user_id` 记录作者归属，AUTHOR 只能维护自己的内容，ADMIN 可管理全部内容
- **技术笔记归属更严格**：只有作者本人能改/删自己的笔记，**ADMIN 也不能操作他人笔记**（`笔记比文章严格`）
- ⚠️ **角色变更需要重新登录才生效**：`PUT /user/{id}/role` 只更新数据库，不会重签 JWT；网关读的是 token 里的 `role` extra，所以被调整的用户必须重新登录，新角色才会生效

### 作者申请（读者 → 作者）

不新增独立申请表：待审状态每人最多一条，直接放在 `user` 表 —— **`role_applied_at` 非空即「有一条待审核申请」**，审核队列复用既有的 `GET /user/list`。

| 动作 | 调用 | 效果 |
|---|---|---|
| 申请 | `PUT /user/role-apply` `{note?}` | `role_applied_at = now`，角色不变；重复申请覆盖为最新理由与时间 |
| 撤回 | `PUT /user/role-apply/cancel` | 清空 `role_applied_at` / `role_apply_note` |
| 通过 | `PUT /user/{id}/role` `{role:"AUTHOR"}` | 角色变 AUTHOR，并清空待审 |
| 驳回 | `PUT /user/{id}/role` `{role:"READER"}` | 角色不变但清空待审 |

- **通过与驳回都复用既有的改角色接口**，`changeRole` 内统一清空申请字段 —— 这样「点通过」与「站长直接改角色」两条路径不会留下自相矛盾的待审状态
- 已提交申请未通过前，该用户仍是 READER，网关按 JWT 角色拦截其创作请求（`POST /notes`、`POST /posts` 等返回 403）
- 申请无服务端频率限制：私人站点，站长自己看得见申请人是谁；空理由也允许提交

### 头像（图片 + 底字双轨）

```bash
# 上传（multipart，字段名固定为 file）
curl -X POST http://localhost:8080/user/avatar -H "Authorization: <token>" -F "file=@me.png"
# 删除（回落底字头像）
curl -X DELETE http://localhost:8080/user/avatar -H "Authorization: <token>"
```

| 项 | 口径 |
|---|---|
| 存储位置 | 由 `stellar.ink.storage.type` 决定：`local`（默认）落 user-service 本地磁盘 `stellar.ink.upload.dir`（dev `./data/uploads`，prod `/app/data/uploads`，环境变量 `UPLOAD_DIR`）；`cos` 存腾讯云对象存储 |
| 访问路径 | `local`：`/uploads/avatars/<服务端生成的文件名>`，**匿名可读**（独立网关路由 `user-uploads`）；`cos`：`https://<bucket>.cos.<region>.myqcloud.com/<key>` 或 CDN 域名，由 COS 直接提供，不经网关 |
| 返回值 | `local` 返回**站内相对路径**（`/uploads/avatars/u1_ab12cd34.jpg`）；`cos` 返回**绝对 URL**。前端 `<img src>` 对两者一视同仁 |
| 文件名校验 | 服务端用 `u{userId}_{uuid8}.{jpg\|png\|webp}` 重新命名，**不采用客户端文件名**，从根上消除 `../` 穿越 |
| 格式校验 | 按文件头（ImageIO 魔数）识别真实格式，只接受 JPG / PNG / WebP；**不信任 Content-Type 与扩展名** |
| 大小限制 | 单文件 1MB（`spring.servlet.multipart.max-file-size` + 业务层字节数双拦），整请求 2MB 与 Nginx `client_max_body_size` 对齐 |
| 换头像 | 先写新对象、写库成功后再删旧对象；删库失败会回收新对象。旧对象不做历史保留 |
| 删除头像 | `avatar_url` 显式 `UPDATE ... SET NULL`（MyBatis-Plus `updateById` 默认忽略 null，直接置 null 会「假成功」） |
| 降级链路 | 前端 `UserAvatar` 组件统一处理：图片 → `avatarText` 底字 → 昵称首字 → `星`；图片加载失败同样降级 |
| 未做 | 无缩略图生成；前端上传前用 canvas 压到最长边 512px 的 JPEG，服务端不引图像库做二次处理 |

### 头像对象存储（腾讯云 COS）

```bash
# 切换到 COS：只需环境变量（密钥只从环境变量注入，配置文件里写不进去）
export STORAGE_TYPE=cos
export COS_BUCKET=stellar-ink-avatars-1459736092   # 必须带 APPID 后缀
export COS_REGION=ap-shanghai
export COS_PUBLIC_BASE=https://cdn.example.com     # 留空则用 COS 默认域名
export COS_SECRET_ID=<CAM 子账号 SecretId>
export COS_SECRET_KEY=<CAM 子账号 SecretKey>
```

- 换存储与回滚的唯一开关是 `storage.type`；改回 `local` 后，库里遗留的 COS 绝对 URL 会被本地实现**安全忽略**（不会误删本地文件），反之 `cos` 实现也只解析自己前缀下的对象键
- 需要的最小权限：`PutObject` / `GetObject` / `HeadObject` / `DeleteObject`（策略资源限定到该桶）
- 桶权限：**公有读私有写**；**不要**开放 ListBucket（验证方法见 `docs/architecture/avatar-minio.md`）
- 密钥管理：使用 CAM 子账号密钥并限定单桶；**绝不用主账号密钥**，绝不入库/入 Nacos
- 详细方案、部署形态与迁移步骤见 [docs/architecture/avatar-minio.md](../architecture/avatar-minio.md)

## 接口一览（经网关调用）

### user-service :8101

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/register` | 注册（开放），注册即登录，返回 `{tokenName, tokenValue, user}` | 公开 |
| POST | `/auth/login` | 登录，返回 `{tokenName, tokenValue, user}` | 公开 |
| POST | `/auth/logout` | 登出（无状态 JWT 语义收口，前端丢 token） | 登录 |
| GET | `/user/authors?ids=1,2` | 批量查询公开作者摘要（最多 100 个，仅返回 id/笔名/头像底字/头像路径） | 公开 |
| GET | `/user/profile` | 当前用户资料（含 `avatarText` 底字与 `avatarUrl` 图片路径） | 登录 |
| PUT | `/user/profile` | 更新资料 `{nickname?, signature?, avatarText?, dailyGoal?}` | 登录 |
| POST | `/user/avatar` | 上传/替换头像（**multipart，字段名 `file`**），返回带 `avatarUrl` 的资料 | 登录 |
| DELETE | `/user/avatar` | 删除头像，回落为 `avatarText` 底字头像 | 登录 |
| PUT | `/user/password` | 修改密码 `{oldPassword, newPassword}` | 登录 |
| PUT | `/user/role-apply` | 读者申请成为作者 `{note?}`（理由 ≤200 字）；已申请则覆盖为最新，返回更新后的 user | 登录（读者即可） |
| PUT | `/user/role-apply/cancel` | 撤回自己的申请，返回更新后的 user | 登录 |
| PUT | `/user/{id}/role` | 调整角色 `{role: READER/AUTHOR/ADMIN}`，返回更新后的 user；**同时清空该用户的待审申请** | ADMIN |
| GET | `/user/list` | 用户列表（供角色管理页枚举）；**同时充当作者申请审核队列**，含 `roleAppliedAt`/`roleApplyNote` | ADMIN |

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
| GET | `/posts/{postId}/comments` | 公开文章评论列表 | 公开 |
| POST | `/posts/{postId}/comments` | 发表评论 `{content}`，返回评论 | 登录（READER） |
| DELETE | `/posts/{postId}/comments/{commentId}` | 软删除评论 | 评论作者或 ADMIN |
| GET | `/tags` | 标签计数（光谱） | 公开 |
| GET | `/search?keyword=` | 标题/正文搜索 | 公开 |

- `orderBy` 取值：`latest` 最新（默认）/ `hottest` 最受回望（按 `glow`）/ `longest` 篇幅最长；一律追加 `id` 倒序保证分页稳定
- `tag` 为 **`LIKE '%tag%'` 子串匹配**（逗号串），不是精确标签匹配 —— 前端拿到结果后需按 `tags.includes(tag)` 二次过滤
- 分页 `size` 服务端硬上限 100（超过会被静默夹到 100），公开列表总条数从 `IPage.total` 取
- 浏览量口径：登录用户在 `post_view` 闸门表里按天去重（同一人同一天多次刷新只计一次），未登录访客每次计数
- 点赞口径：登录用户一人一赞（`post_glow` 唯一键 `(post_id, user_id)`，重复点击 `applied=false` 且不重复计数）；未登录访客一次点击一次计数，`liked` 恒为 `false`
- 评论口径：仅登录用户可发表评论；公开文章评论匿名可读；评论作者或 ADMIN 可软删除，已删除评论不再出现在列表中；正文最多 1000 字，不支持楼中楼与匿名评论

### content-service :8102 - 技术笔记

与文章（`post`）是**两张独立表**：文章重文笔、天然公开；笔记结构化、**可私有**、会过期。

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/notes` | 公开笔记分页列表；`page/size/tag/noteType/keyword/orderBy`。**服务端强制「已发布 + 公开」，不接受可见性参数** | 公开 |
| GET | `/notes/mine` | 我的笔记（含私有与草稿）；`status/visibility/noteType/keyword` | AUTHOR |
| GET | `/notes/{id}` | 详情；**私有笔记仅作者本人可读，其他人一律 404** | 按可见性 |
| POST | `/notes` | 新建 `{title, content, tags[], noteType, visibility, status}`；**缺省 `visibility=PRIVATE`、`status=0` 草稿** | AUTHOR |
| PUT / DELETE | `/notes/{id}` | 更新 / 删除；**只有作者本人**（ADMIN 也不行） | AUTHOR |
| PUT | `/notes/{id}/verify` | 标记「结论仍然有效」，返回 `{verifiedAt}` | AUTHOR |
| POST | `/notes/{id}/viewed` | 记录一次浏览，返回 `{counted}` | 公开 |

- `noteType` 取值：`FIX` 问题解决 ❖ / `PITFALL` 踩坑记录 ⚠ / `TIL` 学习笔记 ✦ / `SCRAP` 碎片 ☄
- `visibility` 取值：`PUBLIC` 公开 / `PRIVATE` 私有；`orderBy` 取值：`latest` / `hottest`（按 `viewCount`）/ `longest`
- **结构约定**：正文用 `## 现象 / ## 环境 / ## 排查 / ## 结论 / ## 参考` 章节表达，前端据此自动生成目录，不额外占用数据库列；列表 `summary` 优先截取「结论」章节
- **私有隔离（硬性约束）**：`PRIVATE` 笔记不得出现在公开列表、标签聚合与搜索里；详情对非作者返回 404（不是 403，避免枚举存在性）；**ADMIN 也读不到他人私有笔记**
- 浏览量口径：仅公开且已发布的笔记计数；按天去重与文章共用 `post_view` 闸门（该表只记「某用户某天已计一次」，与内容类型无关）；作者本人浏览不计
- `verifiedAt` 是笔记区别于文章的核心字段：用于提示「这个结论是否还新鲜」（前端超过 180 天会标为待复核）
- 笔记一期**不做点赞**

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
| GET | `/links` | 已接入的友邻列表（仅返回 `status=1`） | 公开 |
| GET | `/links/pending` | 待审核申请（仅返回 `status=0`） | ADMIN |
| POST | `/links` | 申请接入 `{name, url, description?}`，初始为待审核 | 公开 |
| PUT | `/links/{id}/status?status={status}` | 审核申请：`1` 通过 / `2` 驳回，仅允许审核待审记录 | ADMIN |

友链状态：`0` 待审核、`1` 已接入、`2` 已驳回。待审核与已驳回记录不会出现在公开列表。

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
已有数据库升级脚本（按需各执行一次）：`03_multi-author.sql`（多作者归属）、`04_post_views_glow.sql`（浏览量 `post.view_count` + 点赞明细 `post_glow` + 浏览闸门 `post_view`）、`05_user_role.sql`（补齐 `user.role`）、`06_note.sql`（技术笔记 `note` 表）、`07_role_apply.sql`（`user.role_applied_at` / `role_apply_note`）、`08_user_avatar.sql`（`user.avatar_url` 头像图片路径）、`09_comment.sql`（文章评论 `post_comment`）。拆库：改各服务 `MYSQL_DB` 环境变量。

> `04_post_views_glow.sql` 最后一段会用 `post_glow` 明细重算 `post.glow`，升级前的历史点赞没有 user_id 明细，重算后会计数归零——需要保留旧计数时跳过该段。

## 日志

每服务独立 `logback-spring.xml`：控制台 + 异步文件 `logs/<app>.log`（UTF-8，按天 + 200MB 滚动，30 天）；
dev 环境控制台打印 SQL（mybatis-plus log-impl）。

## 已知边界（后续迭代）

- Sentinel 规则未持久化（sentinel-datasource-nacos 已引入，待配规则）
- 未启用 Redis 令牌桶限流（需 Redis）；生产已有 Nginx 边缘限流（详见 `deploy/docker/nginx/default.conf`，注意 `^/(echos|links)$` 不分方法限流，`GET` 也在限流区内、超限 429）
- 搜索为 LIKE；已做开放注册（`POST /auth/register`，注册即 READER）
- 未做通用附件上传/多租户数据隔离；`echo`/`link` 仍无 `user_id`（友链为全局数据）
- 技术笔记：`/tags` 与 `/stats` **尚未合并**笔记的标签计数（光谱页目前只反映文章）；笔记无点赞、无笔记间反向链接、无全文索引；`note` 已预留 `source_post_id` 概念但**一期未落库**（笔记 ↔ 文章互链留待二期）
- 角色变更需重新登录才生效（见上「角色模型」）；作者申请同样如此（通过后用户要重新登录）
- 点赞不支持取消（只有「已赞」状态，没有取消接口）；浏览量匿名每次计数，无 IP 维度去重
- `GET /health` 经网关不可达（网关无 common-core 依赖、路由未声明），只能直连 :8101/:8102
- 头像没有缩略图与历史版本；`local` 模式下文件在 user-service 本地磁盘，多实例部署应切换到
  `cos` 对象存储。生产使用 `local` 时务必保留 `deploy/docker/data/uploads` 卷，否则容器重建会丢头像
