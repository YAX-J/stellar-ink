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
环境档位（`--spring.profiles.active=`）：`dev`（本机开发，默认）/ `test`（测试机：应用与中间件**同机**，地址默认全 `127.0.0.1`，`SA_TOKEN_JWT_SECRET` 与 `MYSQL_PASSWORD` **必填**；⚠️ 无默认值不等于 fail-fast，见 `AGENTS.md` §5「安全」）/ `prod`（生产）。
种子账号：`stellar / stellar123`。

## 服务与端口

| 服务 | 端口 | 路由前缀 | 表 |
|---|---|---|---|
| gateway-nacos-sentinel | 8080 | 对外唯一入口 | - |
| user-service | 8101 | `/auth/**` `/user/**` `/uploads/**` | user |
| content-service | 8102 | `/posts/**` `/notes/**` `/tags/**` `/search/**` `/meteors/**` `/echos/**` `/links/**` `/stats/**` | post / note / meteor / echo / link |
| ai-service | 8107 | `/ai/**`（网关上已接入，见 §AI） | `ai_*`（模型配置等 AI 域表；不碰业务表） |
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

> 网关已配 `/ai/**` 路由（`/ai/admin/**` 先于「GET 全放行」拦 ADMIN，其余 `/ai/**` 需登录），
> 本机调试也可直连 `http://127.0.0.1:8107`。

**探活（公开）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/health` | AI 能力可用性探活：`service` / `version` / `env` / `available` / `reason` / `downstreamAvailable` / `checkedAt`。**公开**，只回能力状态，不含内网地址、端口、模型名或密钥信息 | 公开 |

```bash
# 经网关或直连本机 ai-service 都可以
curl -s http://127.0.0.1:8107/ai/health
# Python 在跑 => {"code":0,"msg":"成功","data":{"service":"ai-service","available":true,
#              "downstreamAvailable":true,...}}
# Python 没跑 => available=false 且 reason="下游 AI 编排服务未就绪"（原因细节只进服务端日志）
```

- `available` 由**真实探活**决定（`GET <pythonBaseUrl>/health`，连接 2s / 读 3s）：
  Python 自报非 `ok`、回空体、超时、连不上都算不可用；真实原因（含内网地址）只写日志，
  公开响应统一是「下游 AI 编排服务未就绪」，不泄露地址与端口
- 失败形态沿用全局约定：鉴权类错误由网关给出 HTTP 401/403；`/ai/**` 的服务端错误按 `code` 判定
- `stellar-ink-ai`（Python, 8200）**没有对外接口**：它只提供 `/health` 等内部路由，仅供 ai-service 调用
- **本地把 Python 跑起来**：`cd stellar-ink-ai && cp .env.example .env`（Windows：`copy`），
  填两个密钥后 `uv sync && uv run uvicorn app.main:app --host 127.0.0.1 --port 8200`。
  两个密钥都**没有默认值**：`AI_INTERNAL_SECRET`（与 ai-service 逐字一致）缺失时受保护接口一律 401，
  `AI_SECRET_MASTER_KEY` 缺失时面板的「保存 Key」被拒。
  ⚠️ 直接 curl `/qa`、`/writing/*`、`/agent/ask` 会 401，那是**正常**的（必须由 Java 带 `X-AI-*` 签名头调用）；
  非生产环境可用 `GET /internal/whoami` 验证签名链路是否通了

**我的 AI 模型（M12，读者/作者都能用，**登录即可**）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/me/providers` | 列出**我自己**配的模型（不含站长那份全局配置），面板据此显示「已覆盖 / 用全局」 | 登录 |
| POST | `/ai/me/providers` | 保存我的配置；`apiKey` 留空表示沿用已存密钥 | 登录 |
| DELETE | `/ai/me/providers/{role}` | 删除我的配置 → 该角色**自动回落到全局** | 登录 |
| POST | `/ai/me/providers/{role}/check` | 我这份配置的连通性自检（`scope` 会说明测的是哪一份） | 登录 |

四条口径（都与「不只是站长」这个前提绑定）：

① **只放开 `chat` / `fast` / `reasoning`**：`embedding` / `rerank` 传进来会被 **400** 拒掉。
原因是**向量索引只有一份** —— 索引是用某个嵌入模型建的，换另一个模型去检索，
向量不在同一空间，结果不是「差一点」而是**错的**。用户行里若出现这两个角色，
Python 侧会忽略并警告（双保险）。
② **地址只允许公网**（防 SSRF）：`base_url` 是**服务端**拿去发请求的地址，让普通用户填内网地址
等于开放内网探测。loopback / 私网 / 链路本地全部拒绝；站长那份允许内网（自建 vLLM 就在
`127.0.0.1`），但**链路本地（169.254.0.0/16，含云元数据）两档都拒**。
③ **身份只从会话来**：`userId` 取自登录态，请求体里带 `userId` 无效 —— 否则谁都能改别人的配置。
④ **网关门槛是「登录」不是「ADMIN」**：`/ai/me/**` 在网关的「GET 全放行」**之前**拦下
（否则 `GET /ai/me/providers` 会匿名可读，而它返回的是端点与密钥掩码）。

⚠️ 需要先执行 `deploy/sql/18_ai_user_provider_config.sql`（给 `ai_provider_config`
加 `user_id` 并把唯一键改成 `(user_id, role)`）。**没执行时不会报错**：Python 会退回旧查询并记一条
warn，表现是「个人配置保存了但不生效」—— 日志里那句话说明了原因。

**模型配置（全部 ADMIN）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/admin/providers` | 列出所有角色的**当前生效**配置；**密钥只回掩码**（`sk-…9f3a`）与 `apiKeyConfigured`；`modelId` 指向模型库条目（手填为 null） | ADMIN |
| POST | `/ai/admin/providers` | 新增或更新某角色配置（手填路径）；`apiKey` **留空表示沿用已存密钥**；手填会把 `modelId` 置空（等于解除与模型库条目的绑定） | ADMIN |
| DELETE | `/ai/admin/providers/{role}` | 删除某角色配置 | ADMIN |
| POST | `/ai/admin/providers/{role}/check` | 端点连通性自检：只验证 TCP 可达（`scope: tcp_only`），不验证模型与密钥 | ADMIN |
| **PUT** | `/ai/admin/providers/{role}/model` | **把模型库里的某个模型应用到该角色**：请求体 `{"modelId": 1}`；会校验能力匹配，并把该条模型的字段复制成角色当前生效的配置 | ADMIN |
| GET | `/ai/admin/models` | 模型库列表：每条带 `capabilities`（`chat`/`embedding`/`rerank`）与 `boundRoles`（正被哪些角色使用），密钥只回掩码 | ADMIN |
| POST | `/ai/admin/models` | 新增/修改一条模型；`id` 留空=新增，`apiKey` 留空=沿用已存密钥；**同 `baseUrl + model` 唯一** | ADMIN |
| DELETE | `/ai/admin/models/{id}?force=` | 删除一条模型；正被角色使用时会被拦下，`force=true` 则**只解绑**（不动角色当前生效的配置） | ADMIN |
| POST | `/ai/admin/models/{id}/check` | 该模型端点的连通性自检，结论记回库条目 | ADMIN |
| GET | `/ai/admin/providers/runtime` | **含解密后密钥**的运行时配置（仅内网视角）。Python 侧目前**不调用它**，而是直接读同一张 `ai_provider_config` 表并用同一把主密钥解密 —— 两条路读的是同一份数据 | ADMIN |

**RAG 语料投影（`deploy/sql/19_ai_content_snapshot.sql`）**

知识库的内容范围由 content-service 决定（它的 `/internal/corpus` 只返回**已发布文章**与
**已发布且 PUBLIC 的笔记**，见上文「内部接口」），ai-service 把这份清单投影进自己的
`ai_content_snapshot`，Python 再直读这张表 —— 于是 Python 既不碰 `post`/`note`，
「什么算可见」的规则也只有一份。

同步是**对账式**的：拉全量清单（只有 id + `docHash`，不含正文）→ 逐条比对 →
新增/更新变更的、**删除上游不再返回的**（下架、已删、**笔记转私有**都会走到这里）。
默认每 5 分钟一次，也可随时手动触发。

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| POST | `/ai/admin/corpus/sync` | 立刻同步一次；返回 `{upstream,inserted,updated,unchanged,removed,total,failed,reason,syncedAt}` | ADMIN |
| GET | `/ai/admin/corpus/count` | 投影表当前行数（不触发同步） | ADMIN |

⛔ 硬约束：**草稿与私有笔记永远不该出现在 `ai_content_snapshot` 里**。上游已经不返回它们，
删除侧保证「一旦转私有/下架就从投影里消失」。拉取失败时**一行都不删**（宁可这轮什么都不做，
也不能因为一次网络抖动清空知识库）；而上游返回空则**删除全部** —— 隐私优先于「重新嵌入」这个可恢复成本。

```bash
# 手动同步一次（经网关，ADMIN）
curl -s -X POST http://127.0.0.1:8080/ai/admin/corpus/sync
# 同步后应能看到 29 篇文章 + 11 篇公开笔记（本地库当前口径）
mysql -h 127.0.0.1 -uroot -p stellar_ink -e "SELECT kind, COUNT(*) FROM ai_content_snapshot GROUP BY kind"
```

**索引重建（M4 遗留缺口的补齐）**

把「语料真正嵌进向量库」这件事做成一个可调用的入口。此前契约齐全但两侧都没实现，
所以索引从来没有被真正建过（`scripts/index_reconcile.py` 的提示还指过一个不存在的接口）。

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| POST | `/ai/admin/index/rebuild` | 重建向量索引：切块 → 嵌入 → 写 Qdrant。请求体可省略（= 全量重建），也可带 `kind=post_rebuild` + `postId`（单篇）与 `reason`（写进日志便于回溯） | ADMIN |

⚠️ 三条必须知道的现实：

1. **它是同步的**：Python 跑完才返回（没有任务队列）。语料几十篇时只花一次嵌入调用；
   语料涨大后该做的是任务队列，而不是在这里加超时。
2. **返回的 job 查不到历史**：契约里另有 `GET /admin/jobs/{id}`，本阶段未实现 ——
   别拿这个 jobId 去查进度（查不到不等于任务丢了）。
3. **Python 侧的内部路径是 `/admin/index/rebuild`**（不带 `/ai` 前缀）：Feign 客户端没有 path 前缀，
   所以它请求的就是这个路径，必须与 `AiContractPaths.INDEX_REBUILD` 逐字一致 ——
   写错一个字就是 404，而报出来的话会是「Python 不可用」。

```bash
# 全量重建（经网关，ADMIN）
curl -s -X POST http://127.0.0.1:8080/ai/admin/index/rebuild
# 单篇重建
curl -s -X POST http://127.0.0.1:8080/ai/admin/index/rebuild \
  -H "Content-Type: application/json" -d '{"kind":"post_rebuild","postId":1,"reason":"手工验证"}'
# 看结果：测试机 Qdrant 的 dashboard
#   http://124.221.158.32:6333/dashboard   → 集合 stellar_ink_chunks 应有点
```

**模型库与角色配置的分工（`deploy/sql/11_ai_model_library.sql`）**

`ai_provider_config` 是 `UNIQUE KEY uk_role`（一个角色一行），所以「再加一个 chat 模型」
会覆盖原来那行 —— 两个模型之间没法切换，换模型还得把 Key 重填一遍。模型库把两件事拆开：

- `ai_model`：**素材库**，你加进来的每个模型各占一行，并标注它能干什么（能力）；
- `ai_provider_config`：仍是**每个角色当前生效的那份配置**，只多一列 `model_id` 记住来源。

因为 Python 读的还是角色表，所以**Python 侧零改动**。三条一致性约定：

- **改库里那条模型会同步到所有绑定它的角色**（包括轮换 Key）—— 否则会出现
  「库里换了 Key，问答还在用旧的」这种不报错的静默不一致；
- **能力必须匹配**：把纯 chat 模型绑到 `embedding` 角色返回 `code 1001`，消息里说明它支持什么；
- **正被使用的模型不许直接删**：要删得带 `force=true`，且只会解绑、不动角色当前生效的配置，
  免得正在跑的能力突然取不到模型。

```bash
# 1) 加一个模型（能力可多选）
curl -s -X POST http://127.0.0.1:8080/ai/admin/models \
  -H "Authorization: <ADMIN token>" -H "Content-Type: application/json" \
  -d '{"displayName":"主力对话模型","provider":"openai_compatible",
       "baseUrl":"https://api.deepseek.com/v1","model":"deepseek-chat",
       "apiKey":"sk-xxxx","capabilities":["chat"]}'
# 2) 把它应用到 chat 角色（此后问答/摘要都走它）
curl -s -X PUT http://127.0.0.1:8080/ai/admin/providers/chat/model \
  -H "Authorization: <ADMIN token>" -H "Content-Type: application/json" \
  -d '{"modelId":1}'
```

```bash
# 保存一份 DeepSeek 配置（手填路径；Key 只在请求体里出现这一次）
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
- `models` 有三种取值，**面板必须按值分别提示**（表格长得一样，结论完全不同）：
  `fake` = 离线桩（哈希伪向量无语义）、`panel` = 面板里配的真实模型、
  `none` = 这轮策略一次模型都没用到（只跑稀疏召回，不配模型也能跑）
- 模型**只从面板读**（`ai_provider_config` / `AI_PROVIDER_CONFIG_JSON`）：要跑 Dense 就得先配
  `embedding` 角色、要跑 Rerank 就得先配 `rerank` 角色，缺哪个**在跑之前**就返回 400 并说清角色名
- 请求有问题（数据集不支持 / key 重复 / 越界）一律 **400 + `AI_BAD_REQUEST`**；
  语料或数据集文件缺失也是 400，但消息里说清缺哪个文件（环境问题不伪装成 500）；
  模型调用失败由全局处理器转 429（限流）/ 502（上游）
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
- `perStrategy` 每组里除指标外还有 **`errorCount`**：因上游失败（429/超时/5xx）被降级成
  「拒答」的题数。**它非 0 时该行指标不可用** —— 降级的结果与「策略真的全错」
  在表格上一模一样，所以这个计数与 `notes` 里对应的警示必须一起透出，不能被吞掉
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
- **图检索（E5-2）**：策略里可加 `enableGraph: true`，此时请求要带 `graph`
  （**一次 `/wiki/claims` 返回体**）。两条口径：
  ① **开了却没带图**时，那一行会如实回「本次没带图」—— 不是少一列、也不是显示成 Recall=0
  （后者读起来像「图检索效果很差」，实际是「根本没跑」）；
  ② 图检索**一次模型都不调**，所以没有额度也能跑通；但它的上限由**知识图的覆盖率**决定，
  而不是由检索算法决定 —— 对比时要知道这一点。
- **收益判定不在人眼里，在代码里**（`app/rag/eval_service.py::graph_verdict`）：
  基线取**非图策略里主指标（recall@1）最好的那一行**，图检索要高出 2 个百分点以上才算
  `beneficial`；差不够是 `no-benefit`（**按约定删掉这条路径**）；有上游失败（被记成拒答）
  或用的是离线 Fake 模型时一律 `inconclusive`（**不下结论**）。

**作者记忆（M9，Python :8200 内部端点，受内部签名保护、不走网关）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/memory/candidates` | 从一段对话里抽**记忆候选**并立刻用规则校验：`{conversation, maxCandidates, source}` → `{candidates, stats{proposed,kept,dropped}, notes, usageModel}` |
| POST | `/memory/plan` | 拿「已有记忆 + 候选」算写入计划：`{existing, candidates}` → `{toAdd, duplicates[{memoryId,enriched}], conflicts[{memoryId,existingContent,candidateContent}], notes}` |
| POST | `/memory/recall` | 按类型/可信度/有效期过滤出可召回的 id：`{memories, types, minConfidence, limit, expiresAtMs}` → `{memoryIds, notes}` |

三条口径（改契约前先读）：

① **请求体里没有 `userId`** —— 身份只从签名的 `X-AI-*` 头来，由 Java 按登录身份取数；
「替我查用户 X 的记忆」这种参数一旦存在，构造参数就能越权。
所以**用户隔离由取数范围保证**，不是由这几个端点过滤保证。
② **出处校验只在 `/memory/candidates` 那一步做一次**（`quote` 必须真的出现在这次对话里；
用户自己说的话也算证据，但同样要在上下文里）。`/memory/plan` 拿不到上下文，因此**不重新校验**，
也不假装校验 —— 走过场的校验比不校验更糟。
③ **`/memory/plan` 只报告不决定**：冲突（措辞相近但正文不同）**不自动覆盖** ——
可能是同义改写、也可能是作者改了主意，两者都交给人或规则。

⚠️ **Python 侧一条都不落库**：记忆的持久化与状态流转（确认/禁用/删除/清除）在 Java
（`ai_memory` 系列表）。

**作者记忆（M9，经网关；门槛是「登录」而不是角色）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/memory/list` | 列出**我自己**的记忆；`?status=`（pending/active/disabled）、`?type=`（preference/fact/decision）可选。每条都带证据 | 登录 |
| PUT | `/ai/memory/{id}/status` | 启用/禁用：`?status=active\|disabled` | 登录 |
| DELETE | `/ai/memory/{id}` | 删除一条（软删 + 清证据 + 清派生风格画像） | 登录 |
| POST | `/ai/memory/clear` | 全部清除：`{"removed": n}` | 登录 |

三条口径：

① **路径与请求体里都没有 `userId`** —— 身份只从登录态取。
「查某个用户的记忆」这种形状**根本不存在**（`GET /ai/memory/7/list` 会得到 code=404），
所以「用户 A 看不到用户 B 的记忆」不是一条过滤条件，而是接口表达不出来。
② **网关必须把 `/ai/memory/**` 拦在「GET 全放行」之前**（`SaTokenConfigure.AI_MEMORY_PREFIX`）：
它是私密数据，忘了这一步的表现是「列表匿名可读」，而不是报错。
角色门槛刻意留空（读者也能有自己的记忆），但**必须登录**。
③ **删除走独立入口**：`PUT /{id}/status` 传 `deleted` 会被拒绝（code=1001）——
删除要连带清理证据与派生画像，从状态接口走会绕过清理，界面上干净了、数据还在。

> Python 侧另有三个内部端点（`/memory/candidates`、`/memory/plan`、`/memory/recall`）：
> 抽取候选、算写入计划（新增/重复/冲突）、算可召回集合。
> **落库与状态流转只在 Java**，Python 一条都不写库。

**检索审计（M8，ADMIN）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/admin/retrieval-audit/summary` | 最近 N 天（`?days=7`，1..90）的汇总：总数、**拒答率**、**失败率**、被引用最多的文章、被反复问的问题（哈希相同） | ADMIN |

三条口径：

① **不存问题原文**：审计表只存问题的 SHA-256 与长度（问题里可能含个人信息）。
于是仍然能回答「这问题是不是复现性的」（按哈希分组），而要看某一次的具体内容与候选全文，
按 `traceId` 去 `GET /ai/admin/trace/{id}` 看**进程内回放**（那份有内容、但不落库）。
② **拒答与失败分开记**：拒答率高说明**语料没覆盖**（该补文章），失败率高说明**链路坏了**
（该查服务）—— 混成一个「异常率」就没有指导意义了。
③ **记录是 best-effort**：审计写失败只记一条 warn，**绝不影响这次问答**。
这张表也不是评测数据（线上没有标注），**不能拿来算 recall**。

**AI 用量与成本看板（E3-1，全部 ADMIN）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/admin/usage/summary` | AI 调用账汇总：`?days=7`（1..90，默认 7），按场景与模型分组返回调用数、tokens、成本与两个缺口计数 | ADMIN |

```bash
curl -s "http://127.0.0.1:8080/ai/admin/usage/summary?days=7" -H "Authorization: <ADMIN token>"
# => {"code":0,"data":{"days":7,"calls":12,"successCalls":11,"failedCalls":1,
#     "promptTokens":9000,"completionTokens":3000,"totalTokens":12000,
#     "cost":0.4200,"unpricedCalls":2,"untokenizedCalls":3,
#     "byScene":[{"key":"qa","calls":9,...}],"byModel":[{"key":"deepseek-flash",...}]}}
```

- **账记在 Java（ai-service）**：身份（`userId`/`role`）只在 Java 侧、`ai_*` 表归 ai-service、
  每次 AI 调用都必经这一层（也是将来配额拦截的同一层）。表：`deploy/sql/12_ai_call_log.sql`
- **记账是 best-effort**：入库失败只打 `warn`，**绝不把一次成功的调用报成失败**；
  但也不静默 —— 账缺了同样是要处理的故障
- **失败也记**：`success=0` + `errorCode`（异常类名，**不记报文**，报文可能含用户内容）
- **`cost` 必须连着两个缺口计数一起看**：`unpricedCalls`（有 token 但角色没配单价）与
  `untokenizedCalls`（上游没回报 token）任一非 0，金额就只是**下限**，不是「花了这么多」
- **单价按角色配**（`ai_provider_config.price_input_per_million` / `price_output_per_million`，
  元/百万 token，面板下一刀补表单），**记账时快照进账** —— 事后改单价不改写历史账目
- **统计口径**：`calls`/`successCalls`/`failedCalls` 覆盖全部调用；
  token、成本与两个缺口计数**只统计成功调用**（失败调用的用量本来就不存在，
  混进去会把「上游没回报用量」显示成「失败很多」）
- 覆盖路径：`qa`、`qa_stream`、`writing_suggest`、`agent`、`eval`。
  ⚠️ **流式的 token 目前记不到**：用量在 Python 的 `done` 帧里，而按既定设计 Java **不解析事件体**
  （解析等于再抄一份 Python 的事件契约）。这些调用以 `scene=qa_stream` 记入 `untokenizedCalls`；
  要补齐得先给 `done` 事件定义 Java DTO。写作画像是纯统计、一次模型都不调，**刻意不记账**

**AI 配额与并发（E3-2）**

配额不是接口，而是**所有 `/ai/**` 业务出口的前置检查**（与调用账同一个收口 `AiUsageService.around`）：

| 维度 | 触发条件 | 返回 |
|---|---|---|
| 用户 · 每日调用数 | 当天该用户的调用数已到上限 | `code 429` + 可读文案 |
| 用户 · 每日 token | 当天累计 token 已到上限 | 同上 |
| 角色 · 每日调用数 | 当天该模型角色（chat/embedding/rerank）已到上限 | 同上 |
| 并发 | 同一用户「在飞」的请求数超限 | 同上 |

- **额度定义在配置**（`stellar.ink.ai.quota.*`，可调项放 Nacos），**计数存在 Redis**
  （键前缀 `stellar-ink:ai:quota:`，窗口=自然日，跨零点自动换键，无需清理任务）。
  `0` 或负数 = 不限：默认必须「不拦任何人」，否则升级会让功能突然不可用。
- **429 是刻意的**：前端 `api/client.js` 的 `isRateLimited()` 同时认 `status===429` 与 `code===429`，
  文案走「操作太频繁/额度用尽」那条，不会退化成「请求失败」。
- ⚠️ **Redis 不可用时放行**（fail-open）并打 `warn`：配额防的是「把自己的钱烧光」，不是攻击边界；
  Redis 一抖就拒绝所有 AI 请求会把缓存故障升级成整站 AI 不可用。安全边界（JWT 撤销）才用 fail-closed。
- **模型维度目前只统计不拦截**：模型名要等 Python 回来才知道，调用前要拦得再查一次角色配置。
  计数照记（`calls:model:<model>:<day>`），为「钱花在哪个模型上」留数据。
- 检查与累加之间有一瞬间不是原子的（先读齐再累加，不做「加一半再回滚」）：并发下最多多放行
  「同时在飞」的那几个请求。对「防止自己烧钱」足够，换 Lua/CAS 会让这段逻辑难以单测。

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
- 流式版本（`/qa/stream`）的编排与线格式见上；Java 出口（`/ai/qa/stream` 的 `ResponseBodyEmitter`）
  与前端消费方（`utils/sse.js` + `stores/qa.js`）**均已接**，深读页「问星笺」用的就是流式版本。

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
- **前端入口**：阅读页侧栏「问星笺」面板里的**模式切换** ——「一次问答」/「深挖（多步）」；
  深挖的结果会显示**每一步做了什么**（工具名 + 短标签/错误）与预算状态。
  默认停在「一次问答」：深挖更慢也更贵，必须是用户主动切过去。
- ⚠️ **预算只能收紧**：`maxSteps` / `maxToolCalls` 会与**服务端默认**（4 步 / 6 次）取更小值
  （`AiAgentController.bounded`；契约上限 8 / 12）。所以界面**不能**承诺「6 步」——
  传 6 也只会跑 4。前端两档是「快一点（3 步）」与「标准（不带该字段，由服务端决定）」。
- **`doneReason=length` 不是失败**：`answer` 可能为空但 `citations` 往往有值 ——
  前端要显示「预算内没收敛，下面是这几步查到的东西」。三种「没给出答案」的形态必须分开：
  **预算用尽 / 用户停止 / 请求失败**（只有最后一种进 `error`）。
- **停止会真的中止请求**：`api/client.js` 的 `request()` 现在接受调用方的 `signal`
  （不接的话「停止」只是本地不再等，服务端照样跑完多步检索 —— 白烧钱）。
  服务端只在**步与步之间**检查中断，所以界面说的是「已停止等待」而不是「已取消」。

**MCP 工具服务（E3-3：标准化的只读工具协议面）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | **`/mcp`（直连 Python :8200，不经网关）** | JSON-RPC 2.0；方法 `initialize` / `ping` / `tools/list` / `tools/call` | **内部签名**（`X-AI-*`，与其它内网端点同一套） |

```bash
# 必须带内部签名头（Python 直连；对外没有这条出口）
curl -s -X POST http://127.0.0.1:8200/mcp -H "Content-Type: application/json" \
  -H "X-AI-Signature: …" -H "X-AI-Timestamp: …" -H "X-AI-Nonce: …" \
  -H "X-AI-User-Id: 7" -H "X-AI-Role: AUTHOR" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# => {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"search_posts",
#     "inputSchema":{"type":"object","properties":{"question":{…},"topK":{…}},"required":["question"],
#                    "additionalProperties":false},
#     "annotations":{"readOnlyHint":true,"idempotentHint":true,"requiredRole":"READER","timeoutMs":20000}}]}}
```

- **工具集与 Agent 是同一份**（`read_only_tools` + `AGENT_RETRIEVAL`）：协议层另起一套工具，
  迟早会出现「Agent 能查的 MCP 查不到」。当前暴露 `search_posts`（READER）；
  装上作者上下文后还会暴露 `author_style`（**AUTHOR**）。
- **MCP 只是协议层**（计划原文）：它**不替代** Java 的网关门槛、服务内复核与 `ToolBox` 白名单。
  工具仍然「装不进来」而不是运行期判断 —— `read_only=False` 的工具在装配时就被拒。
- **身份只从签名的 `X-AI-*` 头来**（`userId` / `role` 参与签名，内网也改不了）。
  因此 `author_style` 的 `inputSchema` 是**空的**：作者身份不能从参数传。
- **schema 之外的参数一律拒绝**（`-32602`）——这是「客户端不能靠自行构造参数扩权」的落点：
  工具实现忽略未知参数只是运气好，不能当成约定。
- **错误语义分三层**（照 MCP 规范）：
  | 情况 | 返回 |
  |---|---|
  | 坏 JSON / 信封不对 | `-32700` / `-32600`，**HTTP 200** |
  | 未知方法 | `-32601`（`data.supported` 列出可用方法） |
  | 未知工具 / 参数不合法 / 缺 `name` | `-32602`（未知工具带 `data.available`） |
  | 权限不足 | `-32003`（实现定义区间；带 `data.requiredRole`），客户端应当**停止**而不是换参数重试 |
  | 工具执行失败 / 超时 | `result.isError = true`（**不是**协议错误：客户端要把它喂回模型） |
  | 通知（无 `id`） | **202 空体**（回响应就是协议违规） |
- **审计**：每次 `tools/call` 的结果带 `_meta`（`tool` / `role` / `traceId` / `latencyMs` /
  `readOnly` / `idempotent`），与调用账（E3-1）里的 `scene=agent` 记录互相印证。
- ⚠️ **目前只在编排网络内暴露**：对外（站外 MCP 客户端）需要 Java 侧再开一条带鉴权的出口，
  否则拿不到签署过的身份头 —— 没有身份就没有权限判定，等于把只读工具变成匿名可查。

**LLM Wiki 落库与读者侧（E4-2）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/ai/admin/wiki/build` | 抽一轮主张并落库；请求 `{maxPosts?, maxClaimsPerChunk?}`，响应 `{posts, proposed, kept, inserted, updated, skipped, dropped, notes, …}` | ADMIN |
| GET | `/ai/wiki/posts/{postId}/claims` | 按文章读主张（按段落序号排序，**每条都带原文片段**） | **公开** |
| GET | `/ai/wiki/claims/count?postId=` | 某篇文章有多少条主张（读者侧据此决定要不要显示入口） | **公开** |

```bash
curl -s -X POST http://127.0.0.1:8080/ai/admin/wiki/build \
  -H "Authorization: <ADMIN token>" -H "Content-Type: application/json" -d '{"maxPosts":5}'
# => {"code":0,"data":{"posts":1,"proposed":4,"kept":3,"inserted":2,"updated":1,"skipped":0,
#     "dropped":{"quoteNotFound":1},"usageModel":"deepseek-flash","latencyMs":3120,
#     "notes":["1 条主张因**引用找不到原文依据**被丢弃 ……","落库：新增 2 条、更新 1 条、未变动 0 条。"]}}
```

- **两道门槛刻意不同**：构建是 **ADMIN**（批量模型调用、直接花钱，且是「重写全站知识条目」的动作）；
  读取是**公开**（与文章本身的可见性一致）—— 要登录才能看证据的话，Wiki 就变成了「信我」。
- **`dropped` 与 `claims` 同等重要**：它是「模型不行」还是「引用编造被证据校验挡下」的唯一线索，
  Java 侧原样透出、不加工。
- **落库的三种结果分开计数**（`inserted` / `updated` / `skipped`）：重复构建是常态
  （新增文章、换模型、手滑重跑），只回「新增 N 条」会让第二次构建看起来又在膨胀知识库。
- **幂等锚点是 (postId, contentHash, claimText)**，与表上唯一键一致：同一段落的同一版本重复抽取
  不产生重复行；只把「真的变了」的字段（置信度 / 章节路径 / 片段）写下去。
- **缺证据字段的记录不落库**（`postId` / `contentHash` / `text` 任一为空），并在 `notes` 里说明 ——
  写进去就是一条无法核验的「知识」。
- 构建走**调用账**（`scene=wiki`，E3-1/E3-2）：它是批量模型调用，比一次问答贵得多，
  更该记清谁跑了多少篇、花了多少 token。
- ⚠️ 读者侧目前只回**已发布文章**的主张（Python 抽取的语料就是已发布文章）。
  将来语料若含未发布内容，这里必须补一道可见性过滤（已记进 `status.md` 待办）。

**LLM Wiki 主张抽取（E4-1：带证据的知识条目）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | **`/wiki/claims`（直连 Python :8200，不经网关）** | 抽取原子主张并**逐条校验引用**：请求 `{maxPosts?, maxClaimsPerChunk?}`，响应 `{claims, stats, notes, usageModel, latencyMs}` | **内部签名** |

```bash
curl -s -X POST http://127.0.0.1:8200/wiki/claims -H "Content-Type: application/json" \
  -H "X-AI-Signature: …" -H "X-AI-Timestamp: …" -H "X-AI-Nonce: …" \
  -H "X-AI-User-Id: 1" -H "X-AI-Role: ADMIN" -d '{"maxPosts":5}'
# => {"claims":[{"text":"每天写五百字，一年可以累积十八万字","postId":7,"chunkIndex":0,
#      "postVersion":"2026-10-01T00:00:00","contentHash":"hash0",
#      "quote":"每天写五百字，一年就是十八万字","headingPath":"写作方法","confidence":0.9}],
#     "stats":{"proposed":4,"kept":3,"dropped":{"quoteNotFound":1},"posts":1},
#     "notes":["1 条主张因**引用找不到原文依据**被丢弃 —— 这是校验在起作用，不是抽取失败。"],
#     "usageModel":"deepseek-flash","latencyMs":3120}
```

- **每条主张都要能回到原文**（E4 的验收口径，也是它与「让模型写一段摘要」的根本区别）：
  绑定 `postId` + `chunkIndex` + **`postVersion`** + **`contentHash`** + `quote`。
  后两个字段是「文章改了之后只失效受影响的那几条」的依据 —— 增量这块现在不做，但字段先留着。
- **引用必须被观察到**：模型给的 `quote` 必须真的出现在它标注的那个段落里（规范化空白后比对），
  否则**丢弃**。与 Agent 的引用核实是同一条纪律：引用为真，主张才算成立。
- **丢弃分类计数**（`stats.dropped`：`quoteNotFound` / `unknownChunk` / `textTooShort` /
  `textTooLong` / `duplicate`）。只回一个主张列表的话，调用方看不到「提了 4 条、挡了 1 条」，
  而那个比例正是判断「这套抽取能不能用」的关键。
- **`maxPosts` 是成本闸门**（每篇一次模型调用，默认 5、上限 50）：全量抽取是离线批处理，
  不该由一次 HTTP 请求决定（与 Agent 的预算同一条口径）。越界由契约层直接 422。
- **实体与别名（E4-4）**：响应里的 `entities` 是**合并后的实体**，每个提及都带 `claimText` ——
  即「它挂在哪条主张上」。两条规则：
  ① **实体也必须站在留下来的主张上**：`name` 要能在本文某条通过校验的主张或它的原文片段里逐字找到，
  否则丢弃并计入 `dropped.entityNotInText`（只出现在被丢弃主张里的实体会跟着一起消失）；
  ② **合并只做确定性归一化**（全角/半角、大小写、空白、首尾标点），不做语义合并 ——
  「星笺」与「STELLAR INK」是同一个东西，但错合一组的代价是一个说不清的知识条目；
  roadmap §14 第 2 步要求的 ADMIN 审核流还没做，**那条线是后续切片**。
  实体不额外花模型调用：与主张在**同一次**请求里抽取。
- **增量失效（E4-11）**：`POST /wiki/claims` 的请求体多了 `postIds`（**定向重建**：只抽这几篇）。
  ⚠️ 它与 `maxPosts` 是**两个不同的意图**（「按顺序取几篇」vs「就要这几篇」）：
  同时传时以 `postIds` 为准、`maxPosts` 不再截断 —— 否则会出现「报告说 3 篇要重建，
  实际重建的是头 5 篇里的 1 篇」这种查不出的现象。请求里不存在的文章 id 会写进 `notes`。
  失效盘点 `POST /wiki/stale`（**内部签名**，Java 侧 `GET /ai/admin/wiki/stale` 是 ADMIN）：
  输入是库里存的主张锚点 `[{postId, chunkIndex, contentHash}]`，输出三种状态**分开**报 ——
  `current`（不用动）/ `stale`（段落内容变了 → 重建这几篇）/ `orphan`（段落已不存在 → 清理，
  它们再也回不到原文）。判定**只看段落哈希**，不看主张文本（文本是模型输出，重跑本来就可能变）；
  哈希缺失按 `current` 处理（凭缺失判失效会把整库判成过期）。
  ⚠️ **盘点不重建**：重建要花钱打模型，报告是免费的 —— 由 ADMIN 看着报告决定点哪些文章。
- ⚠️ **只回结果、不落库**：持久化归 Java（`ai_wiki_*` 表），Python 不碰库 ——
  与其它 AI 能力的边界一致。
- **主题读取（E4-9）**：`GET /ai/wiki/posts/{postId}/topics`（**公开**）只回**涉及本文**的主题：
  `entities` 是成员（按提及数降序、名字升序），`evidence` 是主题页上那段可核对的原文
  （每段都带 `postId` + `chunkIndex` + `claimText`）。落库的幂等锚点是**成员签名**
  （成员规范化名字排序后的 SHA-256），**不是主题名** —— 名字由成员算出来，成员一变名字就变，
  拿名字当锚点会凭空多出一行、看起来像「发现了新主题」。
- **主题（E4-8）**：响应里的 `topics` 是**共现图上的连通分量**（主题页的原料）——
  同一主题里的实体之间存在一条权重大于阈值的共现边链。三条口径：
  ① 刻意用**连通分量 + 边权阈值**而不是 Louvain：确定、可解释、失败方式看得见；
  ② 文章一多、弱关系连成一片时会得到一个巨大的主题，**正确动作是提高阈值**（只保留被反复
  一起谈论的关系），所以 v1 **不会**假装切开了（那需要模块度优化，属后续切片）；
  ③ `name` 是**关键词组合**（权重最高的几个实体名），不是模型拟的标题 ——
  它读起来不像一句话，但它总是诚实的：名字就是这页里的东西。
  ⚠️ 一次构建只看到这一批文章，所以主题是**增量**长出来的，不是全站快照。
- **实体与关系读取（E4-7）**：`GET /ai/wiki/posts/{postId}/entities`（**公开**，与主张同级）
  返回该文章的实体：`mentions` 是**本文**里它出现的那几句主张（读者在这里核对），
  `mentionCount` / `postCount` 是**全站**计数（两个数字含义不同，别混着显示），
  `relations` 是它与谁被一起谈论（**共现**：`weight` = 被一起谈论的主张条数，且带证据）。
  实体取不到**不影响**知识条目：前端两条路径分别请求、分别降级。
- 契约样例由 `scripts/gen_wiki_fixture.py` 生成（真跑一遍抽取，只把模型换成桩），
  里面**故意含两类被丢弃**（一条编造引用的主张、一个只出现在那条主张里的实体）：
  `tests/fixtures/wiki_claims_result.json`，两侧共读。

**按 traceId 回放链路（E3-4）**

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | **`/internal/trace/{traceId}`（直连 Python :8200，不经网关）** | 返回该 traceId 的结构化事件：`{traceId, found, events:[{atMs, kind, …}]}`；`kind` 为 `retrieval` / `tool` / `model` | **内部签名** |

```bash
curl -s http://127.0.0.1:8200/internal/trace/<traceId> -H "X-AI-Signature: …" …
# => {"traceId":"…","found":true,"events":[
#     {"atMs":…,"kind":"retrieval","topK":4,"posts":3,"refused":false,"latencyMs":41,
#      "sparse":true,"dense":true,"rerank":false},
#     {"atMs":…,"kind":"model","call":"chat","role":"chat","model":"deepseek-flash",
#      "promptTokens":49,"completionTokens":51,"finishReason":"stop","latencyMs":702}]}
```

- **只存结构，不存内容**：事件里是计数、标识与耗时；提示词 / 草稿 / 答案 / 正文片段
  **在代码层被拒绝**（`record_event` 遇到这些字段名直接抛错，而不是静默截断）。要看链路形状，
  Java 侧的调用账（E3-1）与网关日志已经够了；把用户内容再存一份进内存是最容易被忽略的隐私面。
- **有界**：最多 200 个 trace、每个 trace 120 条事件，超出丢最旧的 —— 无上限的环形缓冲就是内存泄漏。
- **进程内**：`found=false` 表示**这一台没有这条链路的记录**（缓冲已淘汰，或链路落在别的副本上），
  不代表「链路不存在」。要跨副本回放必须集中存储（OpenTelemetry / Langfuse）——
  那是需要单独拍板的部署决定。
- 三段链路各自记在**执行它的那一层**：检索在 `RetrievalPipeline.retrieve`、工具在 Agent 的工具循环、
  模型调用在 `OpenAICompatibleProvider`（含失败状态码 —— 「一次 429 让整行指标归零」那类事故
  正是靠它才能在回放里看见）。
- ⏳ **Java 侧的聚合出口见下一节**（把调用账 + Python 事件合成一份「全链路」）；面板暂未做。

**链路回放（E3-4，Java 聚合出口）**

| 方法 | 路径（**经网关**） | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ai/admin/trace/{traceId}` | 按 traceId 回放：Java 侧调用账（`calls`）+ Python 侧链路事件（`events`）+ 说明（`notes`） | ADMIN |

```bash
# traceId 就是网关响应头 X-Trace-Id（32 位十六进制）
curl -s http://127.0.0.1:8080/ai/admin/trace/<traceId> -H "Authorization: <ADMIN token>"
# => {"code":0,"data":{"traceId":"…","pythonAvailable":true,"pythonFound":true,
#     "calls":[{"scene":"qa","providerRole":"chat","model":"deepseek-flash","userId":7,
#               "totalTokens":120,"latencyMs":702,"success":1,"errorCode":null}],
#     "events":[{"atMs":…,"kind":"retrieval","topK":4,"posts":3,"refused":false}, …],
#     "notes":[]}}
```

- **两份数据互补**：`calls` 说「**谁**在什么时候调了、花了多少、成功失败」（身份只有 Java 有）；
  `events` 说「链路**内部**发生了什么」（检索命中几段、调了哪个模型、上游返回什么状态码）。
- **Python 取不到时不藏账**：`pythonAvailable=false` + `notes` 说明原因，`calls` 照常返回 ——
  一次下游故障不该把「本来就有的账」也藏起来，那会让人以为「这次调用根本没发生」。
- **`pythonFound=false` 不是「伪造的 traceId」**：Python 侧的链路缓冲有界且是进程内的，
  可能是被淘汰、也可能这条调用落在别的实例上。要跨副本回放需要 OTel/Langfuse（**待拍板**）。
- **traceId 形状校验**：8–64 位字母数字（不合法返回 `1001`）。它会进 SQL 的 where 与 Feign 的 URL 路径，
  放任任意字符串等于把路径拼接的口子留在最外层。
- 事件字段**由 Python 定义**（不同 `kind` 键不同），`AiTraceDTO.events` 用 `List<Map<String,Object>>`
  搬运而不是建一棵 Java DTO 树：那等于把 Python 的事件契约定死两处。键名与形状由两侧共读的
  `trace_replay_response.json` 守住（`AiContractTest` + `tests/test_trace_contract.py`）。

### 各服务通用

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 简单健康检查（common-core 提供） |
| GET | `/actuator/health` | Spring Boot 健康端点 |

## 内部接口（`/internal/**`，不经网关）

这些接口**只在内网可达**：网关没有任何路由匹配 `/internal/**`，所以从外面访问是 404。
调用方是内网服务（当前只有 ai-service → content-service），**不要**给它们配置网关路由，
也不要指望它们经过 Sa-Token —— 一旦被网关转发，这就是一个无鉴权的数据口子。

### content-service（:8102）— RAG 语料

知识库的「应该包含哪些内容」由 content-service 定义（**可见性规则只有这一份**）：
文章取 `status=1`（0 草稿 / 1 已发布）；笔记取 `status=1` **且** `visibility='PUBLIC'`。
**草稿与私有笔记在任何情况下都不会出现在这两个接口的返回里**（私有笔记只有作者本人可读，
不得进知识库）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/corpus` | 语料清单（**不含正文**）：`{items:[{kind,id,title,docHash,updatedAt}], truncated, limit, maxUpdatedAt}` |
| GET | `/internal/corpus/{kind}/{id}` | 单篇正文（嵌入用）：`{kind,id,title,content,tags,docHash,updatedAt}`；草稿/私有/已删除一律 404 |

`/internal/corpus` 查询参数：

| 参数 | 说明 |
|---|---|
| `since` | ISO-8601（`2026-10-04T18:00:00`）。只返回该时间**之后**修改的（**严格大于**），用于游标续拉 |
| `ids` | 如 `?ids=1,2,3`。注意文章与笔记 id 各自自增，这里是「两种 kind 里 id 命中的都要」；要精确定位用单篇接口 |
| `limit` | 默认 500、上限 1000；传 0 或负数按默认值处理。`truncated=true` 时用返回的 `maxUpdatedAt` 作为下次的 `since` |

```bash
# 全量清单（本机直连，不经网关）
curl -s "http://127.0.0.1:8102/internal/corpus?limit=500"
# 取一篇正文（嵌入前拿内容）
curl -s "http://127.0.0.1:8102/internal/corpus/post/1"
```

⚠️ `docHash` 是**整篇**（标题+正文）的 SHA-256，用途是「这篇有没有变」；
它与索引 payload 里**每个子块**的 `contentHash`（Python 切块时算的，用途是「索引锚点还对得上原文吗」）
**不是一回事**，不要互换使用。

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
