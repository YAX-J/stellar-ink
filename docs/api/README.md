# 星笺后端接口文档（Spring Cloud Alibaba 微服务）

> 对外唯一入口为 **网关 :8080**，API 路径与早期版本一致，前端无感。
> 统一响应 `{ "code": 0, "msg": "成功", "data": ..., "traceId": "..." }`；业务错误 code 非 0（HTTP 200），网关鉴权失败 code=401。
> 版本矩阵与架构详见 [docs/architecture/README.md](../architecture/README.md)。

## 运行

```bash
cd stellar-ink-server && mvn -DskipTests package
deploy\scripts\start-all.bat        # 一键：user/content 两个业务服务 + 网关
```

环境变量：`NACOS_ADDR`（默认 127.0.0.1:8848）、`MYSQL_HOST/PORT/DB/USER/PASSWORD`、`REDIS_HOST/PORT/PASSWORD/DATABASE`、`SA_TOKEN_JWT_SECRET`。
种子账号：`stellar / stellar123`。

## 服务与端口

| 服务 | 端口 | 路由前缀 | 表 |
|---|---|---|---|
| gateway-nacos-sentinel | 8080 | 对外唯一入口 | - |
| user-service | 8101 | `/auth/**` `/user/**` `/uploads/**` | user |
| content-service | 8102 | `/posts/**` `/notes/**` `/tags/**` `/search/**` `/meteors/**` `/echos/**` `/links/**` `/stats/**` | post / note / meteor / echo / link |
| ai-service | 8107 | `/ai/**`（M1 接入网关；M0 只能直连本机 8107 验证 `/ai/health`） | 无（不拥有业务表） |
| stellar-ink-ai（Python） | 8200 | **不配网关路由、不对外暴露**，仅 ai-service 在编排网络内调用 | ai_*（M3 起） |

## 鉴权（Sa-Token，网关统一）

- 登录返回 `tokenName: Authorization` 与 `tokenValue`；后续请求带 `Authorization: <tokenValue>`（无 Bearer 前缀）
- 登出与修改密码会把当前 JWT 的 SHA-256 摘要写入 Redis 撤销列表，网关在令牌自然过期前拒绝它；Redis 不可用时会话校验返回 HTTP 503，不故障放行
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
| 访问路径 | `local`：`/uploads/avatars/<服务端生成的文件名>`，**匿名可读**（独立网关路由 `user-uploads`）；`cos`：配置的 `publicBase`（图片域名，生产经 Cloudflare Worker 代理回 COS）+ `/avatars/<文件名>`，由对象存储直接提供，不经网关 |
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
export COS_REGION=ap-hongkong                      # 生产用香港桶（源站放境外）
export COS_PUBLIC_BASE=https://img.geminix.work    # 图片域名（生产经 Cloudflare Worker 代理）
export COS_SECRET_ID=<CAM 子账号 SecretId>
export COS_SECRET_KEY=<CAM 子账号 SecretKey>
```

- 换存储与回滚的唯一开关是 `storage.type`；改回 `local` 后，库里遗留的 COS 绝对 URL 会被本地实现**安全忽略**（不会误删本地文件），反之 `cos` 实现也只解析自己前缀下的对象键
- 需要的最小权限：`PutObject` / `GetObject` / `HeadObject` / `DeleteObject`（策略资源限定到该桶）
- 桶权限：**公有读私有写**；**不要**开放 ListBucket（验证方法见 `docs/architecture/avatar-minio.md`）
- 密钥管理：使用 CAM 子账号密钥并限定单桶；**绝不用主账号密钥**，绝不入库/入 Nacos
- 启动自检：装配时探一个不存在的键，日志给出「桶可用 / 桶不存在 / 无权限」三种结论
  （只告警不阻塞启动），并写清检查的桶名与 region
- 图片域名建议加 Cache Rule：Eligible + Edge/Browser TTL 1 年（对象名带随机串，换头像即换 URL），
  配置与验证见 [deploy/cloudflare/README.md](../../deploy/cloudflare/README.md)
- 详细方案、部署形态与迁移步骤见 [docs/architecture/avatar-minio.md](../architecture/avatar-minio.md)

## 接口一览（经网关调用）

### user-service :8101

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/register` | 注册（开放），注册即登录，返回 `{tokenName, tokenValue, user}` | 公开 |
| POST | `/auth/login` | 登录，返回 `{tokenName, tokenValue, user}` | 公开 |
| POST | `/auth/logout` | 登出并立即撤销当前 JWT | 登录 |
| GET | `/user/authors?ids=1,2` | 批量查询公开作者摘要（最多 100 个，仅返回 id/笔名/头像底字/头像路径） | 公开 |
| GET | `/user/profile` | 当前用户资料（含 `avatarText` 底字与 `avatarUrl` 图片路径） | 登录 |
| PUT | `/user/profile` | 更新资料 `{nickname?, signature?, avatarText?, dailyGoal?}` | 登录 |
| POST | `/user/avatar` | 上传/替换头像（**multipart，字段名 `file`**），返回带 `avatarUrl` 的资料 | 登录 |
| DELETE | `/user/avatar` | 删除头像，回落为 `avatarText` 底字头像 | 登录 |
| PUT | `/user/password` | 修改密码 `{oldPassword, newPassword}`；成功后撤销当前 JWT，需重新登录 | 登录 |
| PUT | `/user/role-apply` | 读者申请成为作者 `{note?}`（理由 ≤200 字）；已申请则覆盖为最新，返回更新后的 user | 登录（读者即可） |
| PUT | `/user/role-apply/cancel` | 撤回自己的申请，返回更新后的 user | 登录 |
| PUT | `/user/{id}/role` | 调整角色 `{role: READER/AUTHOR/ADMIN}`，返回更新后的 user；**同时清空该用户的待审申请** | ADMIN |
| GET | `/user/list` | 用户列表（供角色管理页枚举）；**同时充当作者申请审核队列**，含 `roleAppliedAt`/`roleApplyNote` | ADMIN |

登录防爆破：user-service 按规范化用户名在 Redis 中维护 15 分钟失败窗口，连续失败 5 次后锁定账号 15 分钟；
锁定与失败计数在多个服务实例之间共享，成功登录会清理失败计数。
`GET /user/authors` 的公开作者摘要使用 Redis 短期缓存，修改笔名、底字或头像后立即失效；`GET /user/profile` 含登录名与申请理由，始终读取 MySQL，不进入共享缓存。

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
| GET | `/tags` | 标签计数（星图页的「标签星座」筛选用） | 公开 |
| GET | `/search?keyword=` | 标题/正文搜索 | 公开 |

- `orderBy` 取值：`latest` 最新（默认）/ `hottest` 最受回望（按 `glow`）/ `longest` 篇幅最长；一律追加 `id` 倒序保证分页稳定
- 公开文章列表、搜索与详情使用 Redis 旁路缓存（1 分钟）；新增、更新、删除时推进缓存版本。浏览量和点赞仍先持久化 MySQL，详情缓存随写失效，列表计数最多延迟一个 TTL。
- `tag` 使用 `FIND_IN_SET` 按逗号分隔成员精确匹配；标签本身禁止包含逗号
- 所有分页接口要求 `page >= 1`、`1 <= size <= 100`，越界返回参数错误；总条数从 `IPage.total` 取
- 浏览量口径：登录用户在 `post_view` 闸门表里按天去重（同一人同一天多次刷新只计一次），未登录访客每次计数；闸门抢占与计数更新处于同一事务
- 点赞口径：登录用户一人一赞（`post_glow` 唯一键 `(post_id, user_id)`，重复点击 `applied=false` 且不重复计数）；未登录访客一次点击一次计数，`liked` 恒为 `false`
- 评论口径：仅登录用户可发表评论；公开文章评论匿名可读；评论作者或 ADMIN 可软删除，已删除评论不再出现在列表中；正文最多 1000 字，不支持楼中楼与匿名评论

### content-service :8102 - 技术笔记

与文章（`post`）是**两张独立表**：文章重文笔、天然公开；笔记结构化、**可私有**、会过期。

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/notes` | 公开笔记分页列表；`page/size/tag/noteType/keyword/orderBy`。**服务端强制「已发布 + 公开」，不接受可见性参数** | 公开 |
| GET | `/notes/mine` | 我的笔记（含私有与草稿）；`status/visibility/noteType/keyword` | AUTHOR |
| GET | `/notes/review` | 我的已发布笔记复核队列；`reviewState/visibility/noteType/keyword/page/size`，默认 `reviewState=DUE`。前端已并入 `/notes/mine?view=review` 视图，接口不变 | AUTHOR |
| GET | `/notes/{id}` | 详情；**私有笔记仅作者本人可读，其他人一律 404** | 按可见性 |
| POST | `/notes` | 新建 `{title, content, tags[], noteType, visibility, status}`；**缺省 `visibility=PRIVATE`、`status=0` 草稿** | AUTHOR |
| PUT / DELETE | `/notes/{id}` | 更新 / 删除；**只有作者本人**（ADMIN 也不行） | AUTHOR |
| PUT | `/notes/{id}/verify` | 标记「结论仍然有效」，返回 `{verifiedAt}` | AUTHOR |
| POST | `/notes/{id}/viewed` | 记录一次浏览，返回 `{counted}` | 公开 |

- `noteType` 取值：`FIX` 问题解决 ❖ / `PITFALL` 踩坑记录 ⚠ / `TIL` 学习笔记 ✦ / `SCRAP` 碎片 ☄
- `visibility` 取值：`PUBLIC` 公开 / `PRIVATE` 私有；`orderBy` 取值：`latest` / `hottest`（按 `viewCount`）/ `longest`
- **结构约定**：正文用 `## 现象 / ## 环境 / ## 排查 / ## 结论 / ## 参考` 章节表达，前端据此自动生成目录，不额外占用数据库列；列表 `summary` 优先截取「结论」章节
- **私有隔离（硬性约束）**：`PRIVATE` 笔记不得出现在公开列表、标签聚合与搜索里；详情对非作者返回 404（不是 403，避免枚举存在性）；**ADMIN 也读不到他人私有笔记**
- 公开笔记列表与详情使用 Redis 旁路缓存（1 分钟）；草稿、私有笔记、我的列表和复核队列不缓存。更新可见性、发布状态、正文或验证时间后推进缓存版本。
- 浏览量口径：仅 `POST /notes/{id}/viewed` 计数，读取详情本身不计；仅公开且已发布的笔记可计数，按天去重与文章共用 `post_view` 闸门（该表只记「某用户某天已计一次」，与内容类型无关），作者本人浏览不计
- `reviewState` 取值：`DUE` 待复核查询（`UNVERIFIED + EXPIRED`）/ `UNVERIFIED` 从未验证 / `EXPIRED` 超过 180 天 / `FRESH` 有效期内；`DUE` 只作为筛选值，不会出现在单条笔记响应中
- 笔记列表与详情返回 `verifiedAt/reviewState/reviewDueAt`；180 天口径由服务端统一计算，`PUT /notes/{id}/verify` 会从当前时间重新续期
- 笔记一期**不做点赞**

### content-service :8102 - 流星

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/meteors?page=1&size=24` | 流星分页列表，返回 `IPage<MeteorVO>` | 公开 |
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

公开评论、回声使用 30 秒缓存；公开流星分页使用 1 分钟缓存；标签、已通过友链和写作统计使用 5 分钟缓存。相应写操作成功后立即失效。普通缓存读取失败会回源 MySQL，接口格式和错误语义不变。

### ai-service :8107 - AI（A1：探活 + 模型配置面板；C：评测台）

> 网关尚未配 `/ai/**` 路由（M1 接），验证只能直连 `http://127.0.0.1:8107`。
> 其余 AI 接口（问答 / 写作建议 / 索引任务）的路径与契约已在 `stellar-ink-ai-client`
> 与 `stellar-ink-ai` 中冻结，落地时逐条补进本文档。

**探活（公开）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/health` | AI 能力可用性探活：`service` / `version` / `env` / `available` / `reason` / `downstreamAvailable` / `checkedAt`。**公开**，只回能力状态，不含内网地址、端口、模型名或密钥信息 | 公开 |

```bash
# 直连本机 ai-service（M1 前只能这样验；M1 起改走网关 /ai/health）
curl -s http://127.0.0.1:8107/ai/health
# => {"code":0,"msg":"成功","data":{"service":"ai-service","available":false,
#     "reason":"下游 AI 编排服务未就绪","downstreamAvailable":false,...}}
```

- `available=false` 不等于故障：下游尚未接线时会**如实上报**（假装健康比暴露未接线更危险）
- 失败形态沿用全局约定：鉴权类错误由网关给出 HTTP 401/403；`/ai/**` 的服务端错误按 `code` 判定
- `stellar-ink-ai`（Python, 8200）**没有对外接口**：它只提供 `/health` 等内部路由，仅供 ai-service 调用
- **本地把 Python 跑起来**：`cd stellar-ink-ai && cp .env.example .env`（Windows：`copy`），
  填两个密钥后 `uv sync && uv run uvicorn app.main:app --host 127.0.0.1 --port 8200`。
  两个密钥都**没有默认值**：`AI_INTERNAL_SECRET`（与 ai-service 逐字一致）缺失时受保护接口一律 401，
  `AI_SECRET_MASTER_KEY` 缺失时面板的「保存 Key」被拒。
  ⚠️ 直接 curl `/qa`、`/writing/*`、`/agent/ask` 会 401，那是**正常**的（必须由 Java 带 `X-AI-*` 签名头调用）；
  非生产环境可用 `GET /internal/whoami` 验证签名链路是否通了

**模型配置（全部 ADMIN）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/admin/providers` | 列出所有角色（`chat`/`fast`/`reasoning`/`embedding`/`rerank`）的配置；**密钥只回掩码**（`sk-…9f3a`）与 `apiKeyConfigured` | ADMIN |
| POST | `/ai/admin/providers` | 新增或更新某角色配置；`apiKey` **留空表示沿用已存密钥**（改模型名不必重填） | ADMIN |
| DELETE | `/ai/admin/providers/{role}` | 删除某角色配置 | ADMIN |
| POST | `/ai/admin/providers/{role}/check` | 端点连通性自检：只验证 TCP 可达（`scope: tcp_only`），不验证模型与密钥 | ADMIN |
| GET | `/ai/admin/providers/runtime` | **含解密后密钥**的运行时配置，供 Python 侧读取（M1 加内网签名后由 Python 调用） | ADMIN |

```bash
# 保存一份 DeepSeek 配置（Key 只在请求体里出现这一次）
curl -s -X POST http://127.0.0.1:8107/ai/admin/providers \
  -H "Authorization: <ADMIN token>" -H "Content-Type: application/json" \
  -d '{"role":"chat","displayName":"DeepSeek Chat","provider":"openai_compatible",
       "baseUrl":"https://api.deepseek.com/v1","model":"deepseek-chat","apiKey":"sk-xxxx"}'
# 自检
curl -s -X POST http://127.0.0.1:8107/ai/admin/providers/chat/check -H "Authorization: <ADMIN token>"
# => {"code":0,"data":{"ok":true,"scope":"tcp_only","latencyMs":12,"message":"地址可达（尚未验证模型与密钥）"}}
```

- **没有读取明文 Key 的接口**：忘了只能重填一次。密文用 AES-256-GCM 存
  `ai_provider_config.api_key_cipher`，主密钥 `AI_SECRET_MASTER_KEY` 只在环境变量里
- 首次配置某角色必须带 `apiKey`；`apiKey` 留空且该角色从未配过 → `code 1001`
- 自检结论只给「可达/不可达 + 可操作提示」，不含主机名与端口

**评测台（C 阶段；当前只在 Python 内网侧，Java 出口见下）**

`stellar-ink-ai` 内部提供三个评测接口。它们**受内部签名保护**（`X-AI-*`，与其它内部路由一致），
不配网关路由；前端要跑评测必须经 `ai-service` 以 ADMIN 门槛转发（下一刀的 `/ai/admin/eval/**`）。

| 方法 | 路径（Python 内部，:8200） | 说明 |
|---|---|---|
| GET | `/eval/datasets` | 可用数据集清单：`id` / `name` / `description` / `cases` / `answerableCases` / `unanswerableCases`。面板下拉框用它填充 |
| GET | `/eval/strategies` | 标准五组策略（`sparse` / `dense` / `hybrid` / `hybrid+rerank` / `sparse+floor`），与 `scripts/compare_strategies.py` 同一份默认值 |
| POST | `/eval/run` | 跑一轮「黄金集 × 多组策略」，返回对比表（`perStrategy`）+ 逐题明细（`cases`）+ 数据来源与诚实提示（`notes`） |

请求/响应要点（完整样例见 `stellar-ink-ai/tests/fixtures/eval_run_request.json`）：

```bash
# 直连 Python（本机开发）；正式路径是经网关 /ai/admin/eval/run（下一刀）
curl -s -X POST http://127.0.0.1:8200/eval/run \
  -H "Content-Type: application/json" -H "X-AI-Signature: <HMAC>" \
  -d '{"dataset":"golden_v1","strategies":[{"key":"sparse","enableDense":false}]}'
# => {"dataset":"公开文章黄金集 v1","corpusSource":"seed-sql:02-init-data.sql",
#     "corpusPosts":29,"corpusChunks":41,"models":"fake",
#     "perStrategy":{"sparse":{"recall@1":0.8333,...}},"cases":[...],"notes":[...]}
```

- `strategies` 省略时用标准五组；`key` 必须唯一（重复会 400，否则对比表两列同名、逐题明细无法区分）
- `models` 目前固定 `"fake"`：Dense 两列**只代表通路接对了**，不代表真实语义质量 ——
  这条写进响应的 `notes`，面板要原文展示，不能让用户把 Fake 的数字当结论
- 请求有问题（数据集不支持 / key 重复 / 越界）一律 **400 + `AI_BAD_REQUEST`**；
  语料或数据集文件缺失也是 400，但消息里说清缺哪个文件（环境问题不伪装成 500）
- 新增数据集/策略默认值要同时改：`app/schemas/eval.py`、本文件、前端面板与
  `stellar-ink-ai/tests/fixtures/eval_run_request.json`

**评测台的 Java 侧契约**

`stellar-ink-ai-client` 里已有对应契约，路径常量见 `AiContractPaths`：

| 方法 | 内网路径 | Java 契约 |
|---|---|---|
| GET | `/eval/datasets` | `PythonAiClient#evalDatasets()` → `List<Map<String,Object>>` |
| GET | `/eval/strategies` | `PythonAiClient#evalStrategies()` → `List<Map<String,Object>>` |
| POST | `/eval/run` | `PythonAiClient#evalRun(EvalRunRequestDTO)` → `EvalRunResponseDTO` |

- 契约样例由两侧测试共读：`eval_run_request.json`（请求）与 `eval_run_response.json`（响应，
  由 `scripts/gen_eval_response_fixture.py` 真实跑出来，不做手工修饰）
- `EvalRunResponseDTO.perStrategy` 用 `Map<String,Object>`：指标集合由 Python 侧决定，
  Java 再定义一遍等于把指标名写死两处；形状由契约测试守着
- 降级沿用统一口径：Python 不可用时抛 503 业务异常，**不返回空对比表**
  （那会让面板显示「0 分」而不是「服务没连上」）

**评测台（对外出口，全部 ADMIN）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/admin/eval/datasets` | 可评测的数据集清单（面板下拉框）：`id` / `name` / `cases` / `answerableCases` / `unanswerableCases` | ADMIN |
| GET | `/ai/admin/eval/strategies` | 标准策略组（默认五组，**与命令行脚本同一份**）：面板据此渲染勾选项，不在前端另写一份默认值 | ADMIN |
| POST | `/ai/admin/eval/run` | 跑一轮检索评测：请求体见下（`strategies` 留空用标准五组），响应含 `perStrategy` 对比表、`cases` 逐题明细与 `notes` 诚实提示 | ADMIN |

```bash
curl -s -X POST http://127.0.0.1:8080/ai/admin/eval/run \
  -H "Authorization: <ADMIN token>" -H "Content-Type: application/json" \
  -d '{"dataset":"golden_v1","strategies":[{"key":"sparse","enableDense":false}]}'
# => {"code":0,"data":{"dataset":"公开文章黄金集 v1","models":"fake",
#     "perStrategy":{"sparse":{"recall@1":0.8333,...}},"cases":[...],"notes":[...]}}
```

- 网关侧由 `/ai/admin/` 前缀统一拦 ADMIN（任何方法，**先于「GET 全放行」**）；
  服务内 `AiEvalController` 再复核一次角色，网关漏配也不会漏出去
- 调用 Python 的请求由 `InternalSignatureFeignInterceptor` 统一加 `X-AI-*` 签名头
  （身份取自 Sa-Token，traceId 取自 MDC）；密钥 `AI_INTERNAL_SECRET` 缺失时**拒绝签名**而不是降级为不签名
- `notes` 必须原样透出到面板：里面写着「Fake 向量不代表真实语义质量」等口径，
  Java 不能吞掉这些提示

**星海问答（D 阶段）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/ai/qa` | 就全站已发布文章提问：请求体 `{question, topK?}`，响应 `{answer, citations, doneReason, usage, evidenceSufficient}` | **登录**（READER 及以上） |

```bash
curl -s -X POST http://127.0.0.1:8080/ai/qa \
  -H "Authorization: <token>" -H "Content-Type: application/json" \
  -d '{"question":"作者为什么坚持写博客，而不是把内容交给时间线？","topK":5}'
# => {"code":0,"data":{"answer":"…","citations":[{"postId":1,"title":"…","chunkIndex":0,
#     "snippet":"…","score":0.83}],"doneReason":"stop","usage":{"model":"fake",…},
#     "evidenceSufficient":true}}
```

- 门槛是**登录**而不是角色：这是读者功能，任何人登录后都能问；网关按「未列举路径」规则
  走 `StpUtil.checkLogin()`（`/ai/admin/**` 才是 ADMIN）
- `evidenceSufficient=false` 时前端**必须**显示「文章里没有找到依据」，不允许渲染成空白答案；
  `doneReason=refused` 与之一致
- 引用是**可点回原文**的：`postId` 决定跳哪篇、`chunkIndex` 决定段落、`snippet` 是原文片段
- `usage.model` 为 `fake` 表示当前是离线自测（种子语料 + Fake 模型），前端会挂一个提示；
  真实模型接上后这个字段就是模型名
- Java 侧只做协议转换（`AiAskDTO` → 内部 `QaStreamRequestDTO`），**不拼答案、不改引用**；
  空问题在服务端就被 `@NotBlank` 拦下（`code=1001`），不会白花一次检索

**星海问答（流式，经网关）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/ai/qa/stream` | 流式问答：`text/event-stream`，逐帧转发 Python 的 SSE | **登录** |

```bash
curl -N -s -X POST http://127.0.0.1:8080/ai/qa/stream \
  -H "Authorization: <token>" -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"question":"一年写十八万字的方法是什么？","topK":5}'
# => data: {"type": "meta", "model": "fake", "questionLength": 14, "topK": 5}
#    data: {"type": "citation", "citation": {"postId": 20, "title": "…", …}}
#    data: {"type": "delta", "text": "…"}
#    data: {"type": "done", "answer": "…", "doneReason": "stop", "usage": {…},
#           "evidenceSufficient": true}
```

- **事件体原样透传**：ai-service 用 `ResponseBodyEmitter` 逐帧转发，不解析、不重新编码
  （`QaSseFrame.raw()`）。Java 只解析出 `type` 用于日志与审计 —— 少一层映射就少一处会与 Python 契约分叉的地方。
- 为什么不用 `SseEmitter`：它会**按事件名分帧**（`event:` 头 + data），而本协议刻意把类型放在 JSON 里。
- 为什么不用 Feign 拉这条流：Feign 的解码器是「拿到完整 body」语义，会把 SSE 退化成一次性响应 ——
  用户仍要等模型把整段话说完。这里用 JDK 自带的 `java.net.http.HttpClient`（**不引新依赖**）单独开一条通道。
- **取消传播**：浏览器断开 → Spring 在下次 `send` 时抛 `IOException` → 关掉下游句柄 →
  上游连接断开 → Python 的生成器被关闭 → 模型停止生成。这是「关掉页面就不再烧 token」的完整链路。
- 上游不可用时返回一帧 `error`（`AI_UPSTREAM_UNAVAILABLE`）后收尾，**不返回空流**：
  空流会被前端当成「回答完了」，于是「服务坏了」伪装成「没有依据」。
- 整体上限 120s（`STREAM_TIMEOUT_MS`），到点回收连接并记一条 warn 日志。
- **前端消费方**（深读页「问星笺」，`utils/sse.js` + `stores/qa.js`）：
  用 `fetch` 读 `response.body` 并按空行切帧，**不用 `EventSource`** —— 后者只支持 GET，
  而问答必须 POST（问题有 500 字上限，塞进 query string 既难看又会进日志）。
  `meta` 到就显示模型标识、`citation` 到就渲染引用、`delta` 边到边追加正文、`done` 才收尾；
  **没收到 `done` 就提示「回答中断了」**，不装作答完了。
- 中止链路两端都做了：前端 `abort()`/离开页面关闭读取（`reader.cancel()`），
  Java 侧 `IOException` 分支关掉下游句柄 —— 用户一放手，模型就停。
- 代理侧必须关缓冲：dev 由 `vite.config.js` 的 `/ai` 代理，生产由 nginx 的 `/ai` location
  （`proxy_buffering off` + `proxy_read_timeout 120s` + 独立限流档）。
  漏了 `proxy_buffering off`，「逐字生成」会被攒成一整块再吐出来 —— 前端仍在转圈等到最后。

**星海问答（Python 内部，供 ai-service 调用）**

| 方法 | 路径（Python 内部，:8200） | 说明 |
|---|---|---|| POST | `/qa` | 一次问答：检索 → 引用 → 提示词 → 模型 → 结论。请求体是既有的 `QaStreamRequest`（`question` / `conversationId` / `topK`），响应是 `QaAnswer`（`answer` / `citations` / `doneReason` / `usage` / `evidenceSufficient`） |
| POST | `/qa/stream` | 同一套编排的 SSE 版本：`text/event-stream`，事件顺序 `meta → citation* → delta* → done`（`error` 是旁路事件）。空问题在契约层 422；语料缺失在开流前返回 JSON 400 |

```bash
# 直连 Python（本机开发；正式路径是经网关的 /ai/qa）
curl -s -X POST http://127.0.0.1:8200/qa \
  -H "Content-Type: application/json" -H "X-AI-Signature: <HMAC>" \
  -d '{"question":"作者为什么坚持写博客，而不是把内容交给时间线？","topK":5}'

# SSE：注意 -N 关掉 curl 自己的缓冲，否则看起来「没有流式」
curl -N -s -X POST http://127.0.0.1:8200/qa/stream \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -H "X-AI-Signature: <HMAC>" \
  -d '{"question":"一年写十八万字的方法是什么？","topK":5}'
# => data: {"type": "meta", "model": "fake", "questionLength": 14, "topK": 5}
#    data: {"type": "citation", "citation": {"postId": 20, "title": "…", …}}
#    data: {"type": "delta", "text": "…"}
#    data: {"type": "done", "answer": "…", "doneReason": "stop", "usage": {…},
#           "evidenceSufficient": true}
```

**SSE 线格式（跨语言，改必须两侧同时改）**

- 一帧就是 `data: {json}\n\n`，**事件类型写在 JSON 里**（`type` 字段）而不是用 `event:` 名。
  这样 Java 侧不必维护一张事件名表，前端用同一个解析器即可。
- 顺序固定：`meta` 一定第一个到（前端立刻进入「生成中」而不是干等），`citation` 一定先于 `delta`
  （引用来自检索，不必等模型），`done` 一定最后一个（**缺了它前端会永远停在生成中**）。
- `error` 是旁路事件：一旦发出就不会再有 `done`，前端据此区分「说完了」与「断了」。
- 静默期（模型第一个 token 之前）插 `: ping` 注释行 —— 心跳不是事件，解析时要跳过；
  没有它，反向代理会把静默连接当死连接回收，表现为「答到一半突然断开」。
- 响应头带 `Cache-Control: no-cache` 与 `X-Accel-Buffering: no`：后者是关掉 Nginx 的响应缓冲，
  漏了的话「流式」会被攒成一整块再吐出来，前端看到的仍是转圈等到最后。
- **取消传播**：浏览器断开 → ASGI 关闭响应生成器 → 队列里的编排任务被取消 → 上游 HTTP 流关闭。
  Python 侧用「生产者任务 + 队列」实现，`finally: task.cancel()` 是这条链路的落点。

- **引用来自检索结果，不来自模型输出**：模型只被要求写 `[1] [2]` 编号，真实的
  `postId` / `title` / `chunkIndex` / `snippet` / `score` 由服务端贴回去 —— 否则引用无法定位回原文。
  引用列表**只包含真正送进模型的那几段**，不会多列。
- **拒答是数据不是文案**：证据不足时返回 `doneReason=refused` + `evidenceSufficient=false` + 明确文案，
  前端据此显示「文章里没有找到依据」，而不是渲染一片空白。
- 没有候选时**不会调用模型**（省一次费用，也不给模型编造的机会）；模型自己拒答时**保留引用**
  （「找到了段落但答不出」与「什么都没找到」是两种信息）。
- 当前语料是种子内容包、模型是 `FakeProvider`，所以 `usage.model` 会如实显示 `fake`。
  接上真实嵌入模型后，`minDenseScore` 要按评测台标定 —— 离线 Fake 向量余弦只有 0.03 量级，
  给它设一个「看起来合理」的下限会让向量通路**静默失效**（混合检索退化成纯 BM25 而不报错）。
- 流式版本（`/qa/stream`）的编排与线格式见上；**Java 出口（`/ai/qa/stream` 的 `ResponseBodyEmitter`）
  与前端消费方还没接**，等这两件一起做 —— 先造一条没人消费的流式通道是本仓库明确避免的事。

**星笺 Copilot（D 阶段：写作建议）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/ai/writing/suggest` | 生成写作候选：请求体 `{task, draft, instruction?, tone?, candidateCount?}`，响应 `{task, candidates[{text, rationale}], usage}` | **AUTHOR** |
| POST | `/ai/writing/style` | 当前作者的**写作画像**：请求体 `{maxSamples?}`（1..50，默认 20），响应 `{authorId, evidenceSufficient, profile, notes}` | **AUTHOR** |

```bash
curl -s -X POST http://127.0.0.1:8080/ai/writing/suggest \
  -H "Authorization: <AUTHOR token>" -H "Content-Type: application/json" \
  -d '{"task":"polish","draft":"今晚星星很多。我坐在窗边写字。","tone":"restrained"}'
# => {"code":0,"data":{"task":"polish","candidates":[{"text":"…","rationale":"…"}],
#     "usage":{"model":"fake-copilot",…}}}
```

- `task`：`title` / `outline` / `continue` / `polish` / `tags` / `summary`；`tone`：`keep` / `restrained` /
  `colloquial` / `concise`。取 `continue`/`polish`/`tags`/`summary` 时**必须带草稿**（Python 契约会 422）
- **只返回候选，绝不写正文**（红线 §7.4）：作者看过差异预览、点「采纳」之后才走既有 `/posts/**`。
  客户端多写的字段（例如 `autoApply`）不会渗进内部契约 —— 那个字段在内部 DTO 里根本不存在
- 草稿是**未发表的私有内容**：只随本次请求传给模型，不入索引、不落库；日志也不记草稿正文
- 门槛是 AUTHOR：建议要读草稿，而草稿属于创作内容；网关按 `/ai/writing/` 前缀拦，服务内再复核
- 当前模型是**离线桩**（`usage.model=fake-copilot`）：它按提示词要求的 JSON 格式给出「草稿句子切片」，
  不做任何改写 —— 只为让链路与前端可测；接上真实模型后该字段就是模型名

**前端入口（执笔页 `/write` 侧栏，仅作者可见）**

- `components/ai/CopilotPanel.vue` + `stores/copilot.js`：6 个功能按钮（润色 / 续写 / 提纲 / 标题 / 标签 / 摘要）
  → 候选 + 逐行**差异预览**（`components/ai/DiffView.vue` 渲染 `utils/diff.js` 算出的行级 LCS 差异）
  → 每条候选一个「采纳」按钮。
- **采纳动作按任务区分**（纯函数 `utils/copilot-action.js`，有 8 条断言守着）：
  润色＝替换正文、续写＝插到光标处、提纲＝追加到末尾、标题＝只填标题、标签/摘要＝只复制。
  未知任务**退到「只复制」**——宁可多一步手工，也不能猜成「替换正文」而抹掉作者写好的内容。
- **面板里没有「自动应用」开关**，也不会在生成后自动改写：正文的每次变更都由作者点一下触发，
  随后仍走既有的草稿自动保存 / 发布链路（`/posts/**`）。
- 差异预览比的是**发请求那一刻的草稿快照**（`requestedDraft`），不是「现在的草稿」：
  作者在结果返回后继续打字时，预览不会跟着漂移；新请求会清掉旧候选，避免把上一轮结果当成这一轮的建议。

**星笺 Copilot（Python 内部，供 ai-service 调用）**

| 方法 | 路径（Python 内部，:8200） | 说明 |
|---|---|---|
| POST | `/writing/suggest` | 同上（契约 `WritingSuggestRequest` / `WritingSuggestResult`）；「模型没按格式回答」返回 **502 + `AI_UPSTREAM_UNAVAILABLE`**，与「没有建议」区分开 |
| POST | `/writing/style` | 写作画像（E1）。按 `authorId` 过滤种子语料并现算，**不落库、不进索引**；样本不足时返回 `evidenceSufficient=false` + 带篇数与字数的 `notes` |

**写作画像（E1：写作记忆）**

```bash
curl -s -X POST http://127.0.0.1:8080/ai/writing/style \
  -H "Authorization: <AUTHOR token>" -H "Content-Type: application/json" \
  -d '{"maxSamples":20}'
# => {"code":0,"data":{"authorId":1,"evidenceSufficient":true,
#     "profile":{"sampleCount":20,"charCount":3071,"medianSentenceChars":24.0,
#                "shortSentenceRatio":0.1694,"clausesPer100Chars":7.29,
#                "commonPhrases":["所以我","不清楚"],"transitions":["所以","其实"],
#                "topTags":["随笔","写作"]},
#     "notes":"口径：只统计已发布文章的正文；…"}}
```

- **只量不写**：画像是派生数据，删掉文章重算就变；服务端**不落库、不进索引、不参与检索**，
  因此没有需要清理的状态，重复调用无副作用。
- **不引用原句**：`commonPhrases` 只放**反复出现（≥3 次）**的 3–6 字字组，绝不摘录整句。
  两个理由：中文里「作者的一句原话」常常就是最私人的部分；而把原句塞进提示词，
  下一轮模型会照抄，读者一眼就看得出来。**阈值降到 1 会被参数校验直接拒绝**。
- **只用已发表正文**：当前语料是种子内容包（只有已发布文章）。接真实数据源时
  `_samples_for` 必须显式写成 `status = published` —— 草稿进画像等于把未发表内容泄露进提示词。
- **作者 id 来自登录身份**，不是请求体：`AiWritingStyleDTO` 里根本没有 `authorId` 字段，
  多传会被忽略。画像不含原句，但「写了多少、爱用什么词」本身也是隐私。
- 口径细节：长度按「中日韩字符按字 + 拉丁按词」计（`Redis` 算一个词），代码块/行内代码/链接先剔除，
  句长取**中位数**（比平均数稳）。这些口径写在 `notes` 里随响应返回，前端直接展示给作者。
- `evidenceSufficient=false` 时 `profile` 为 `null` 而不是一堆 0 —— **0 与「没量」是两件事**；
  `notes` 里带着实际篇数与字数门槛，作者一眼知道还差多少。
- 当前端入口：执笔页侧栏 Copilot 面板里的「我的写作画像」折叠块（默认收起，只读）。

**只读 Agent（E2：预算受限的多步检索）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/ai/agent/ask` | 多步检索问答：请求体 `{question, maxSteps?, maxToolCalls?}`，响应 `{answer, citations, doneReason, steps, toolCalls, interruptedBy, usageModel, latencyMs}` | **登录** |

```bash
curl -s -X POST http://127.0.0.1:8080/ai/agent/ask \
  -H "Authorization: <token>" -H "Content-Type: application/json" \
  -d '{"question":"一年写十八万字的方法是什么？","maxSteps":4}'
# => {"code":0,"data":{"answer":"","citations":[…],"doneReason":"length",
#     "steps":[{"index":0,"thought":"…","tool":"search_posts","label":"检索到 2 段","error":""}],
#     "toolCalls":1,"interruptedBy":"budget","usageModel":"fake","latencyMs":42}}
```

- **门槛是登录**，与一次问答相同：Agent 查的仍是站内已发布文章，
  不比问答多出任何权限 —— 这一点必须守住，否则「Agent」会变成绕过权限的借口。
- **工具全部只读**，且是**装不进来**而不是运行期判断：`ToolBox` 构造时会拒绝
  `read_only=False` 的工具（红线 §7.4：第一版 Agent 工具全只读）。
- **预算是硬上限**，三个都要：步数、工具调用次数、观察字符数。任一触顶立即收尾并如实标
  `doneReason=length`。只限步数挡不住「一步里塞十个工具调用」，只限次数挡不住
  「一次观察把整篇文章灌回来」。**服务端默认 4 步 / 6 次**，比契约上限（8 / 12）更紧，
  且客户端只能收紧（`bounded()` 取 min）—— 预算不能由每个请求自己决定。
- **`doneReason=length` 不是失败**：此时 `answer` 可能为空，但 `citations` 往往有值 ——
  前端要显示「查到了这些，但没能在预算内收敛」。Java 侧**原样透传**，
  绝不会因为「答案为空」就改成错误。
- **引用必须被观察到**：模型只能标 `postId`（片段与分数一律由工具结果补齐），
  而且标注的引用要能在工具返回结果里找到，否则丢弃；模型一条都没标对时，
  退化成「把观察到的引用原样带上」—— 答案是依据它们写的，一条都不给反而无法核对。
- **中断是状态不是异常**：`interruptedBy=caller`（用户关页面）或 `budget`。
  中断只在**步与步之间**检查，工具执行中途不打断（将来加写操作时这条边界很关键）。
- 离线形态：模型用 `FakeProvider` 时解析不出决策 JSON，于是会走「格式不符」分支并在预算内收尾，
  最终如实返回 `doneReason=length` + `steps[].error`。**这不是「假 Agent」**：
  循环、预算、引用核实、中断全是真的，只有「模型怎么想下一步」是桩。
- 前端入口尚未接线（Agent 比一次问答慢且贵，等有真实模型与配额后再决定放哪个页面）。

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
- 技术笔记：`/tags` 与 `/stats` **尚未合并**笔记的标签计数（星图页的「标签星座」目前只反映文章）；笔记无点赞、无笔记间反向链接、无全文索引；`note` 已预留 `source_post_id` 概念但**一期未落库**（笔记 ↔ 文章互链留待二期）
- 角色变更需重新登录才生效（见上「角色模型」）；作者申请同样如此（通过后用户要重新登录）
- 点赞不支持取消（只有「已赞」状态，没有取消接口）；浏览量匿名每次计数，无 IP 维度去重
- `GET /health` 经网关不可达（网关无 common-core 依赖、路由未声明），只能直连 :8101/:8102
- 头像没有缩略图与历史版本；`local` 模式下文件在 user-service 本地磁盘，多实例部署应切换到
  `cos` 对象存储。生产使用 `local` 时务必保留 `deploy/docker/data/uploads` 卷，否则容器重建会丢头像
