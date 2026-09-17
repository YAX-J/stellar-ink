# AGENTS.md — 星笺 STELLAR INK 编码约定

> 所有 Agent / 协作者在本仓库写代码前必须先读本文档；与本文冲突的旧代码不代表规范可以放松。
> 回复用户、写注释、写提交信息一律使用中文（代码标识符用英文）。

## 1. 项目是什么

「星笺 · STELLAR INK」：一个把文章比作星辰的夜间写作博客。设计基调是深色星空、
缓慢、诗意 —— **任何 UI 改动不得破坏这个气质**（不引入亮色系大色块、不用圆角/字体之外的花哨组件库）。

后端微服务架构**对齐参考工程 `E:\resume_project\enterprise_digital_platform`**（模块划分、
配置文件风格、公共组件分层均以其为准），业务域换成本项目的文章/流星/回声/星链。

```
stellar-ink/
├── stellar-ink-web/                前端：Vue 3 + Vite + Pinia + Vue Router（已接网关）
├── stellar-ink-server/             后端：Spring Cloud Alibaba 微服务（已跑通）
│   ├── common-components/          公共组件聚合（非独立运行）
│   │   ├── shared-model/           共享模型：Response/ErrorCode/异常/DTO/VO
│   │   ├── common-core/            基础设施：全局异常/TraceId/MP配置/健康检查/Redis工具
│   ├── gateway-nacos-sentinel/     网关 :8080（WebFlux：路由/CORS/Sa-Token 鉴权/Sentinel）
│   ├── user-service/   :8101       登录认证、站长资料（表 user）
│   ├── content-service/:8102       文章/流星/回声/星链/写作统计（按领域分包）
│   └── stellar-ink-ai-client/      Java → Python AI 客户端契约（按 AI 实施任务逐步建设）
├── stellar-ink-ai/                 Python AI 编排服务（按 AI 实施任务逐步建设）
├── tools/nacos/                    Nacos Server 本体（gitignore，不入库）
├── docs/architecture/              微服务架构说明
├── docs/api/README.md              接口文档（改接口必须同步更新）
├── docs/ai/                        AI：技术路线（README）/ 实施顺序（implementation-roadmap）/ 开发流程（development-workflow）
├── deploy/sql|scripts/             数据库初始化脚本 / 一键启动脚本
└── deploy/docker/                  生产 Docker Compose 部署（Nacos/网关/2 服务/前端 Nginx，详见其 README）
```

## 2. 常用命令与端口

```bash
# 前端（端口 5173）
cd stellar-ink-web && npm install && npm run dev      # 开发
npm run build                                          # 构建验证

# 后端（网关 8080 对外；Nacos 8848；服务 8101-8102）
cd tools/nacos/bin && startup.cmd -m standalone       # 1. 先起 Nacos
cd stellar-ink-server && mvn -DskipTests package       # 2. 构建
deploy\scripts\start-all.bat                           # 3. 一键起全部（或按模块手动 java -jar）
                                                       #    注：该脚本为本地私有文件（含服务器连接信息），已 gitignore 不入库

# 生产部署（Linux 服务器 Docker Compose；MySQL/Redis/Qdrant 复用服务器已有容器）
cd deploy/docker && cp .env.example .env && vi .env    # 1. 填 MYSQL_PASSWORD / SA_TOKEN_JWT_SECRET
docker compose up -d --build                           # 2. 构建 + 启动（步骤详见 deploy/docker/README.md）
```

- 提交前必须验证：前端 `npm run build` 通过；后端 `mvn package` 通过，且启动后通过网关（:8080）curl 过改动到的接口。
- 测试中文请求体时，git-bash 的 curl 会以 GBK 发送导致 500，先把 body 写成 UTF-8 文件（或用 node fetch）。
- 后端改代码前先停对应进程，否则 Windows 下 jar 被锁定，`mvn package` 的 repackage 会失败。

## 3. 通用工程规范

- **Git**：功能走 `feature/*` 分支；提交信息格式 `type(范围): 中文主题`，正文用 `-` 列要点。
  常用 type：feat / fix / docs / chore / refactor。
  **代码一律由用户自行提交，AI 不执行 `push`**；AI 只按主题拆分改动并给出 commit message 供用户参考，实际提交由用户完成。
- **禁止入库**：`node_modules/`、`dist/`、`target/`、`.vite/`、`.idea/`、`tools/`、`logs/`（见根 .gitignore）。
- 新增依赖要克制：前端不加 UI 组件库；后端版本必须整体联动（见下），先在父 pom `dependencyManagement` 登记。
  **唯一例外**：技术笔记编辑器用 CodeMirror 6（`@codemirror/*` + `@lezer/*`，共 7 个包），
  因为 Obsidian 式「行内渲染 Live Preview」无法用 textarea 实现，而这些包是编辑器内核而非 UI 组件库。
  **硬性约束**：该依赖只允许被 `views/notes/NoteEditView.vue` 通过 `defineAsyncComponent` 懒加载，
  绝不可在其它页面 import —— 否则 520KB（gzip 180KB）会进入首屏包。新增依赖前先确认没有更轻的替代。
- 所有文本文件 UTF-8（Windows 下注意别让 IDE 存成 GBK）。
- 文档同步：改了接口/启动方式/目录结构，必须同步更新 `docs/api/README.md`、`docs/architecture/README.md` 和本文档。

## 4. 前端规范（stellar-ink-web）

### 架构与数据流
- 目录职责固定：`views/<页面>/XxxView.vue`、`components/{canvas,common,post}/`、
  `stores/`（Pinia，业务数据统一从网关取数）、`api/mock.js`（仅保留视觉常量与写作提示）、`composables/`、`utils/`、`styles/`。
- 组件**不直接请求后端**：数据一律进 store；将来接真实 API 时只改 store 的取数来源，不动视图。
- 路由在 `router/index.js` 统一注册，页面组件懒加载；新页面必须同时在导航（`components/common/TopNav.vue`）登记。

### 导航（顶部横向，对齐原型 b12）
- 全站唯一导航是 `components/common/TopNav.vue`：`position:sticky` 顶栏，未滚动时透明、
  滚动超过 40px 才浮出底色与分隔线；结构为「品牌 ✦ 星笺 | 一级导航居中 | 搜索图标 + 执笔按钮 + 头像菜单」。
- **一级导航只放「内容维度」，共 6 项**：此刻 / 星图 / 笔记 / 流星 / 回声 / 星链。
  判断某页该不该进一级导航：它是不是一类内容的主入口？「筛选维度」（标签）、
  「个人管理」（我的笔记、复核、星籍、账号）「一次性动作」（执笔、登出、换主题）都不占一级位——
  前者并进所属内容页，后两者分别收进页面内视图切换与右上头像菜单。
- 已合并/删除的入口（不要再新增回来）：光谱 → 星图（标签与年份叠加筛选）、
  复核 → 我的笔记（`/notes/mine?view=review`）、星籍 → 账号（`/account` 的「我的星籍」面板）。
  旧路径在 `router/index.js` 里保留 `redirect`，老书签不会撞 404。
- 主区域样式在 `styles/base.css`：`.main` 不再给导航留左右边距（顶栏 sticky 自带占位），
  页面宽度上限由 `.page`（1240px）/`.page-wide`（满宽）决定，别再写 `margin-left:96px` 这类旧偏移。
- 需要 `position:fixed` 的页面浮件（阅读进度条、回到顶部、toast）要避开 64px 高的顶栏：
  进度条 `z-index` 高于 50（否则会被顶栏毛玻璃糊掉），toast 的 `top` 用 78px。

### 样式（最重要）
- **颜色/字体/圆角/缓动一律用 `styles/tokens/variables.css` 的 CSS 变量**
  （`--primary/--on-primary/--amber/--teal/--rose/--ink*/--bg*/--line/--r-*/--ease-*`），禁止硬编码色值。
- 全站三主题（night/dusk/dawn）靠 `[data-theme]` 切变量实现（选择器同时匹配 `:root` 与 `body`：
  `index.html` 的内联脚本在 body 解析前就把主题写到 `<html>` 上以消除首屏闪烁）；
  新组件必须保证在破晓（浅色）主题下可读——即只用语义变量、不用固定深色。
- **填充主色上的文字必须用 `--on-primary`，不要写 `#fff`**：夜色/暮色的 `--primary` 偏亮，
  白字对比度只有 3.3:1 / 2.2:1，`--on-primary` 是各主题分别标定过的（5.6 / 7.3 / 6.3）。
- **对比度基线**：`--ink-faint` 及以上都按 WCAG AA（对 `--bg` ≥4.5:1）实测过；改任何色值前先复算，
  别让「破晓主题下读不清」的回归再来一次。
- 共用元件类（.btn/.kicker/.section-head/.chip/.field/.side-card/.echo-form/.map-tip/.foot）
  放 `styles/components.css`；页面私有样式写各自 view 的 `<style scoped>`。
- **标题与标签的版式**：`TECH NOTES · 程序员的标本册` 这类页面标签一律跟在标题**后面**
  （不要另起一行压在标题上方），走 `SectionHead` 的 `kicker` 属性——它内部是 `.title-row`
  （标题 + `.kicker` 同一基线）。自己画标题的页面（深读页、404）用
  `<div class="title-row"><h1>…</h1><span class="kicker">…</span></div>` 保持同样版式。
  例外：首页 hero 最上面那行是「日期问候」而不是页面标签，仍留在标题上方。
  另外 `.page > .section-head:first-child` 会去掉区块标题的 72px 上边距——页面标题贴着页面顶部，
  不要再给页面级 SectionHead 手动补 margin-top。
- 字体：Space Grotesk / Noto Serif SC / Noto Sans SC / JetBrains Mono（index.html 引入），
  正文 300 字重、标题用衬线 900，等宽字体用于元数据。
- 无障碍与动效是**全局基线**，写在 `styles/base.css`，新组件不要再各写一套：
  `:focus-visible` 统一焦点环；`prefers-reduced-motion: reduce` 时关掉全部动画与过渡（星野/漂流瓶都要停）。

### 错误处理与提示
- **会话失效判定必须 code/status 联合**：用 `api/client.js` 导出的 `isAuthError()`，它判定
  `code === 401 || status === 401`。原因见上：网关放行 GET，读接口的未登录由服务端兜底返回
  **HTTP 200 + code 401**，只看 `status` 会漏判（曾导致 token 过期后页面只显示「读取失败」）。
- 全局兜底链路固定：`api/client.js` 出错 → `utils/bus.js` 发事件 → `main.js` 清会话并带
  `redirect` 跳登录。**页面不要各自再写一套 401 处理**。
- 用户可见提示统一 `emit(TOAST, { type, message, traceId })`，由 `components/common/ToastCenter.vue`
  渲染；`traceId` 要透出给用户（可点击复制）便于排障。
- 表单校验/面板内提示仍写页面内的 `.msg`；`request(..., { silent: true })` 可抑制全局 toast，
  避免同一错误提示两遍。
- 限流有独立文案：`isRateLimited()` 判定 429（Nginx 边缘限流，注意 `GET /echos`、`GET /links`
  也在限流区内），不要退化成「请求失败（429）」。

### 正文渲染与阅读体验
- 正文 Markdown 由 `utils/markdown.js` 解析成块级结构，视图按白名单标签渲染（**不引依赖**）。
  该文件先 `escapeHtml` 再插入自己的标签，链接有协议白名单，因此 `v-html` 处是安全的。
- **编辑区**（仅技术笔记）由 `components/editor/MarkdownEditor.vue` 提供，基于 CodeMirror 6：
  Live Preview 用 `ViewPlugin` 从语法树算装饰 —— 光标所在行显示源码，其余行折叠标记并渲染。
  折叠用 `Decoration.replace()` 实现，产出的是「双层 widgetBuffer + 隐藏 span」，
  **它在像素上与未折叠状态难以区分，判断折叠是否生效必须看 DOM 或计算样式，不要靠截图**。
  笔记编辑页另有 `applyExternalContent`：编辑器已有内容时拒绝被空字符串覆盖
  （父组件 modelValue 在保存/切换时会短暂变空，照单全收会清空正文）。
- 新增 Markdown 语法：先在 `parseMarkdown` 加块类型 → 视图加 `v-else-if` 分支 →
  在 `ReadView.vue` 的 `<style scoped>` 补样式（不要用全局样式）。
- 阅读偏好（字号/行距/正文宽度）存 `stores/settings.js` 的 `read`，通过 CSS 变量
  `--read-fs / --read-lh / --read-w` 下发给正文，组件里不要硬编码字号。
- 阅读位置记忆用 sessionStorage（`settings.rememberPosition/positionOf`），只在进入文章时恢复一次。
- 深读页顶部是**单行工具条** `.read-bar`：左「← 返回星域」、右「⚙ 阅读设置」（`.read-tools` 挂在右端，
  设置面板 `position:absolute` 展开、不推动正文）。**不要再把这两个按钮拆成上下两行**——
  那样进页面先看到两个孤零零的按钮，中间留一大片空白。笔记详情页的返回按钮同样贴着页面顶部。

### Canvas 惯例
- 画布位图尺寸 = CSS 尺寸 × 2，用 `utils/canvas.js` 的 `fitCanvas`，别自己写。
- 动画统一 `requestAnimationFrame` 循环：`onMounted` 启动、`onUnmounted` 取消；
  绘制数据要过滤**带坐标的点位数组**，不能拿原始业务数据（曾因 `arc(undefined)` 静默失败排查很久）。

### Vue 已踩过的坑（新代码必须规避）
- 组件模板**保持单根节点**：多根组件上 `v-show`/指令不生效（Vue 只警告不报错）。
- `.reveal` 入场动画 `fill-mode: both` 会把 opacity 钉死在 1，之后任何静态 `opacity` 都压不过；
  需要动态改透明度的元素要么不加 reveal，要么同时 `animation: none`。
- 祖先元素上的 `filter`/`transform` 会变成 `position:fixed` 后代的包含块，
  且 `opacity` 会连带全部后代——需要 fixed 悬浮件时别把这些写在父级。

## 5. 后端规范（stellar-ink-server，Spring Cloud Alibaba 微服务）

### 版本矩阵（必须整体联动升级，不可单点调整；对齐参考工程）
- Spring Boot 3.2.12 / Spring Cloud 2023.0.6 / Spring Cloud Alibaba 2023.0.3.4 / Java 17
- MyBatis-Plus 3.5.15（分页拦截器需额外引 mybatis-plus-jsqlparser）、Druid 1.2.20、
  Sa-Token 1.44.0（JWT 无状态模式）、springdoc 2.3.0 + knife4j 4.5.0

### 架构与边界
- 拓扑/端口/调用关系见 `docs/architecture/README.md`；对外唯一入口是网关 :8080，API 路径与前端约定保持稳定。
- 服务按变更与运行边界拆分：user-service 负责 `user`；content-service 内按
  `post/note/meteor/echo/link/stats` 领域分包，负责 `post`、`note`、`meteor`、`echo`、`link`，统计直接查询文章数据。
- 共享库模式：一个 `stellar_ink` 库；user-service 与 content-service 只读写各自负责的表。
- **鉴权在网关**（Sa-Token，JWT 无状态模式 `StpLogicJwtForStateless`）：放行 GET/OPTIONS、
  `POST /auth/login`、`POST /auth/register`、公开写接口（`POST /echos`、`POST /links`、
  `POST /posts/{id}/glow`、`POST /posts/{id}/viewed`、`POST /notes/{id}/viewed`）；
  其余对 `/posts|/notes|/meteors|/links|/user` 的写请求 `StpUtil.checkLogin()`。
  下游服务用 `AuthHelper.loginId()`（StpUtil 验签）取用户 id，不校验路由级权限。
- **鉴权失败的返回形态不统一，前后端都必须 code/status 联合判断**：网关层拦截是 HTTP 401/403；
  但 GET 在网关是放行的，token 失效时读接口由服务端 `NotLoginException` 兜底，
  返回 **HTTP 200 + body code=401**。
- **当前 JWT 可撤销**：登出与修改密码会把令牌 SHA-256 摘要写入 Redis，TTL 等于令牌剩余寿命；
  网关用 Reactive Redis 在路由前检查，已撤销返回 HTTP 401。Redis 不可用时返回 503，不得故障放行；
  登录/注册不依赖撤销列表，避免 Redis 故障时把恢复入口一并锁死。
- **角色门槛（三档，权限累积，仅做操作开关、不做数据隔离）**：`READER 读者` ⊃ 基础读与公开互动；
  `AUTHOR 作者` = READER + 写/改/删文章、技术笔记、发射/删除流星；`ADMIN 站长` = AUTHOR + 友链审核 + 调整用户角色。
  角色在登录/注册时写入 JWT 的 `role` extra（`Role` 枚举见 shared-model，键 `Role.JWT_KEY`）；
  网关读 `StpUtil.getExtra(Role.JWT_KEY)` 做门槛（文章/笔记/流星写需 AUTHOR，`PUT /links/{id}/status`、
  `GET /links/pending`、`PUT /user/{id}/role`、`GET /user/list` 需 ADMIN；注意 `GET /links/pending`、`GET /user/list`、`GET /posts/mine`、
  `GET /notes/mine`、`GET /notes/review` 都是「读」但需更高角色，**必须在网关「GET 全放行」之前单独拦下**），
  角色不足返回 403；服务内用 `AuthHelper.currentRole()/requireAtLeast()` 做防御性复核。
  注册固定 READER，种子账号 stellar 为 ADMIN。
- ⚠️ **角色变更需重新登录才生效**：`PUT /user/{id}/role` 只改库、不重签 JWT，而网关读的是 token 里的
  `role` extra。调整角色后必须让该用户重新登录，新角色才会生效（后续可考虑实现「重新签发 token」）。
- 当前两个业务服务之间没有同步调用；将来确需跨服务调用时再建立独立契约模块，
  使用 Feign + FallbackFactory + resilience4j；`/internal/**` 不得配置网关路由。
- 跨服务 DTO/VO 放 `shared-model` 按服务子包（`dto/post`、`vo/user`…），服务间共享，**不放业务服务内**。

### 工程约定（对齐参考工程）
- 包结构：user-service 使用 `com.stellarink.user/{controller,service,service.impl,mapper,pojo,config}`；
  content-service 使用 `com.stellarink.content.<domain>/{controller,service,service.impl,mapper,pojo}`。
  实体包叫 **pojo**（不是 entity），服务接口在 service、实现放 `service/impl`。
- 启动类模板：`@SpringBootApplication @ComponentScan(basePackages={"com.stellarink.<svc>","com.stellarink.common"})
  @EnableDiscoveryClient @MapperScan("com.stellarink.<svc>.**.mapper")`；需要 Feign 的加
  `@EnableFeignClients(basePackages="com.stellarink.serviceapi.feign")`。
- 公共模块：`shared-model`（Response/ErrorCode/BusinessException/DTO/VO）、
  `common-core`（GlobalExceptionHandler(Servlet+Reactive)/TraceIdFilter/LogInterceptor/
  MybatisPlusConfig/SimpleHealthController/AuthHelper/BusinessExceptionHelper/RedisUtils）。
- Redis 基础工具使用 `StringRedisTemplate + ObjectMapper`，普通值统一存 JSON；只供 user/content
  两个 Servlet 业务服务使用，网关不得引入阻塞式 Redis 工具。连接参数统一走 `REDIS_HOST/PORT/PASSWORD/DATABASE`。
- 所有接口统一返回 `Response<T>`（code/msg/data/traceId）；业务校验失败抛 `BusinessException`
  （用 `BusinessExceptionHelper.of(...)`），全局处理器带 traceId 并写 MDC。

### 配置文件风格（照参考工程，每个服务统一 5 件）
| 文件 | 内容 |
|---|---|
| `application.yml` | 极简：port + 应用名 + `profiles.active: dev` |
| `application-dev.yml` | `spring.config.import: optional:nacos:<app>-dev.yaml` + Nacos 配置/发现 + **Druid** 数据源 + sa-token + springdoc/knife4j + actuator 全暴露 + 日志降噪 |
| `application-prod.yml` | 生产：敏感项全走环境变量（`MYSQL_PASSWORD`、`SA_TOKEN_JWT_SECRET`、`NACOS_ADDR`） |
| `nacos-application-dev.yml` | 上传 Nacos 的动态配置模板（Data ID：`<app>-dev.yaml`），放敏感/可调项 |
| `logback-spring.xml` | 控制台 + 异步文件 `./logs/<app>.log`（UTF-8，按天+200MB 滚动，30 天） |

- Nacos 地址统一用环境变量 `NACOS_ADDR`（默认 127.0.0.1:8848）、命名空间 `NACOS_NAMESPACE`
  （默认 public，config 与 discovery 必须同空间，3 个服务要一起设）；
  MySQL 用 `MYSQL_HOST/PORT/DB/USER/PASSWORD`；JWT 密钥用 `SA_TOKEN_JWT_SECRET`。
- **网络超时必须显式配**（本地跨公网连服务器时尤其重要：曾因链路抖动十几秒 + 默认无限等，
  把 `/tags` 挂到 23s）。约定：Redis `timeout` dev `500ms` / prod `1s`、`connect-timeout: 2s`
  （缓存是旁路，超时到点即回源；网关撤销检查 fail-closed，缩短超时只是更快暴露 503，不放行）；
  JDBC URL 必须带 `connectTimeout=3000&socketTimeout=15000`（Connector/J 默认 0 = 无限等，
  只能等操作系统放弃，Windows 约 21s）；Druid `max-wait: 5000`、`validation-query-timeout: 3`。
  改这些值时同步 `nacos-application-dev.yml` 模板，别只改本地文件。

### 数据库
- 表名小写单数，列 snake_case，主键 `BIGINT AUTO_INCREMENT`；MySQL 8 / utf8mb4。
- DDL：`deploy/sql/01_schema.sql`（幂等）+ 种子 `02_init-data.sql`（与前端展示用的种子内容对齐）；
  已有库升级脚本按顺序各执行一次：`03_multi-author.sql`（多作者归属）、
  `04_post_views_glow.sql`（`post.view_count` + `post_glow` 点赞明细 + `post_view` 浏览闸门）、
  `05_user_role.sql`（补齐 `user.role`；早期库缺该列，不补会导致所有用户查询报 Unknown column）、
  `06_note.sql`（技术笔记 `note` 表）、`07_role_apply.sql`（`user.role_applied_at` / `role_apply_note`）、
  `08_user_avatar.sql`（`user.avatar_url` 头像图片路径）、
  `09_comment.sql`（文章评论 `post_comment`）。
- 作者申请口径：**不建独立申请表**，待审状态用 `user.role_applied_at` 非空表示（每人最多一条待审，
  最新即当前）；审核队列复用 `GET /user/list`，前端不再发第二个请求。
  **通过与驳回都复用 `PUT /user/{id}/role`**，并在 `changeRole` 内统一清空申请字段 ——
  不要新加「驳回」专用接口，否则「点通过」与「直接改角色」会出现两套代码路径与不一致状态。
- 头像口径：`avatar_url` 存**可直接给 `<img src>` 用的地址**（`local` 为站内相对路径
  `/uploads/avatars/u{userId}_{uuid8}.{ext}`；`cos` 为 COS/CDN 绝对 URL）。上传 `POST /user/avatar`
  （multipart，字段名 `file`）、删除 `DELETE /user/avatar`。**存储位置由 `stellar.ink.storage.type`
  一个开关决定**（`local` 默认 / `cos`），业务层依赖 `storage/ObjectStorage` 接口，
  换存储=新增一个实现类，可随时回滚。
  - `local`：文件落 `UPLOAD_DIR`（生产 Docker 卷 `deploy/docker/data/uploads`，必须保留）；
    读取走 `/uploads/**` 独立网关路由（匿名可读）。**限制：文件与库必须同机可达**，
    本地连远程库或跑多实例时会出现「上传成功但图片 404」。
  - `cos`：腾讯云对象存储。`bucket` 必须带 APPID 后缀、`region` 形如 `ap-shanghai`；
    `public-base` 必须填**浏览器能访问到**的地址（CDN 域名），填内网端点或 localhost 会导致全站图片 404。
  - 校验三件套收在 `storage/AvatarValidator`（服务端改名 + ImageIO 魔数认格式 + 1MB 双拦），
    两个实现共用，**对象存储实现不得绕过**；`ObjectStorage.delete` 必须尽力而为、
    只删自己前缀下的对象（切换存储后遗留的另一种形态 URL 要安全忽略）。
  - 密钥**刻意不作为配置项**：只从环境变量 `COS_SECRET_ID` / `COS_SECRET_KEY` 读取（见 `StorageProperties`），
    生产用 CAM 子账号密钥并限定单桶，绝不用主账号密钥。
  - 换头像先写新对象、写库成功后再删旧对象（删库失败回收新对象）；**删除头像必须用
    `LambdaUpdateWrapper.set(..., null)`** —— MyBatis-Plus 的 `updateById` 默认忽略 null 字段，
    直接 `setAvatarUrl(null)` 会「接口成功、刷新又回来」。
  - 前端一律用 `components/common/UserAvatar.vue` 渲染，降级链路「图片 → avatarText 底字 → 昵称首字 → 星」，
    不要在视图里各写一套取字/降级逻辑。完整方案见 `docs/architecture/avatar-minio.md`。
- 浏览量口径：登录用户在 `post_view` 闸门表按天去重（每人每天只计一次），未登录访客每次计数。
  **该表与内容类型无关，文章与笔记共用**（只记「某用户某天已计一次」）。
  点赞口径：登录用户一人一赞（`post_glow` 唯一键 `(post_id,user_id)`），未登录访客计次不记态。
  `post.glow` 是计数冗余，判断「我是否已赞」一律以 `post_glow` 为准。
- 技术笔记口径：`note.visibility` 为 `PRIVATE` 时**只有作者本人可读**，其他人（含 ADMIN）读详情一律 404，
  且不得出现在公开列表、标签聚合与搜索里；`note` 的归属判定 `ensureOwned` **比文章更严格**
  —— 只有作者本人能改/删，ADMIN 也不能操作他人笔记。
- 已知坑：`user` 在部分环境是保留字，DDL/实体用反引号 `` @TableName("`user`") ``。
- **种子数据的 id 必须与时间同向递增**：文章/笔记/流星/回声的默认列表是 `ORDER BY id DESC`
  （见 `PostServiceImpl.applyOrder` 的 latest），id 与 `created_at` 反向排列会让首页「最近星尘」、
  星图长卷、笔记「全部」视图出现时间倒挂。现有内容包（post 16+ / note 11+ / meteor 6+ /
  echo 8+ / link 7+ / comment 3+ / user 4+）已按此约定排好，续写时沿用。
- **直接写库（绕过服务）不会推进 Redis 缓存版本**：写完要清 `stellar-ink:content:cache:*`
  （`KEYS` 后 `DEL`），否则 `/tags`、`/stats/overview` 和列表页仍是旧值；
  **绝不要动 `stellar-ink:auth:revoked:*`**（那是 JWT 撤销列表，删掉等于让已登出的令牌复活）。

### 日志（slf4j + logback-spring.xml）
- 一律 `@Slf4j`；关键业务动作 info，登录失败/未授权/业务异常 warn（不含敏感信息），未捕获 error。
- 访问日志由 common-core 的 `LogInterceptor` 输出（`API-ACCESS 方法 路径 状态 耗时`）；
  链路追踪 `TraceIdFilter`（MDC + `X-Trace-Id` 响应头），异常响应带 traceId。

### 安全
- 密码只存 BCrypt；`SA_TOKEN_JWT_SECRET` 生产用环境变量覆盖，
  网关与所有业务服务的 jwt-secret-key 必须一致，代码里不得出现新的硬编码密钥。

## 6. 当前状态与边界（不要越界开发）

- 已完成：前端内容页（此刻 / 执笔 / 星图 / 寻星 / 笔记 / 我的笔记 / 笔记详情与编辑 / 流星 / 回声 / 星链 /
  深读 / 404）+ 鉴权与账号页（登录 / 注册 / 账号），均已接网关 :8080；
  后端微服务化（网关 + user/content 两个业务服务 + Nacos 注册/配置中心 + Sentinel + Sa-Token）。
- Redis 接入已完成：`common-core` 提供 `RedisUtils` 与故障回源的 `RedisCache`；登录失败计数与账号锁定、
  JWT 撤销、公开作者摘要、公开文章/笔记及标签/统计/评论/友链/流星/回声读模型已接 Redis。完整用户资料、
  草稿、私有/审核数据、JWT 原文、浏览闸门、点赞明细和持久计数不进缓存；Redis 限流与分布式锁尚未实现。
- **AI 当前状态**：技术路线（`docs/ai/README.md`）、实施顺序（`docs/ai/implementation-roadmap.md`）、
  开发流程（`docs/ai/development-workflow.md`）均已定稿，**从 M0「契约与工程骨架」开始实施**：
  一轮一个可验证切片、一个主题一个提交，M0 全程用 Fake Adapter 且不需要任何密钥；
  M0–M5 完成前不并行开发多 Agent、GraphRAG 与微调。`ai-client`、`stellar-ink-ai`
  只实现路线中已列出的切片内容，不在未明确拆分任务时自行扩展。文件上传、
  全文检索引擎（现用 LIKE）、Redis 限流、Sentinel 规则持久化仍待用户明确要求后再动。
- **已做开放注册**（`POST /auth/register`，注册即登录返回 token，角色固定 READER）：文章与流星已记录 `user_id` 作者归属，
  AUTHOR 只能创作和维护自己的内容，ADMIN 可管理全部内容；友链仍是全局数据。
- 前端已接网关：`src/api/client.js`（fetch 封装 + token）+ Pinia stores（会话与业务数据）；
  页面 `/login` `/register` `/account` 支持改密、登出和 ADMIN 角色管理，文章/流星/回声/友链均读取真实接口；
  多作者署名通过 `/user/authors` 批量补全，写作页支持草稿自动保存、恢复、删除与发布。
- 阅读体验一期已完成：登录后按 `redirect` 回跳、未登录可浏览公开页（`/account` 与写作需登录）、
  全局 toast + traceId 排障、深读页 Markdown 渲染 + 目录 + 阅读设置 + 阅读位置记忆、
  文章浏览量（登录用户按天去重）与点赞去重（一人一赞 + 已赞态）；`orderBy` 支持
  `latest / hottest / longest` 三种排序。
- 文章评论一期已完成：公开文章评论列表、登录读者发表评论、评论作者或 ADMIN 软删除；
  正文最多 1000 字，前端深读页展示评论者头像与昵称。
- 技术笔记一期已完成：独立 `note` 表与 `/notes` 接口（列表 / 我的 / 详情 / 增删改 / 标记已验证 / 浏览计数）、
  公开与私有两档可见性、正文用 `## 现象/环境/排查/结论/参考` 章节表达并由前端自动生成目录、
  列表按技术栈热度分区、`summary` 优先截取「结论」章节；前端页面 `/notes`、`/notes/mine`、
  `/note/:id`、`/note/edit`，导航符号 ❖。
  正文渲染抽到 `components/common/MarkdownBody.vue`（文章与笔记共用，含代码块复制按钮）。
- 技术笔记复核已完成：AUTHOR 在 `/notes/mine?view=review` 查看自己的已发布笔记，按
  `DUE / UNVERIFIED / EXPIRED / FRESH` 筛选；180 天时效由后端从 `verified_at` 实时派生，
  该视图支持搜索、分页、编辑跳转和就地标记「仍然有效」（原独立页 `/notes/review` 已并入，
  接口 `GET /notes/review` 不变）。
- 作者申请已完成（读者 → 作者闭环）：`PUT /user/role-apply` 提交/覆盖申请（带可选理由）、
  `PUT /user/role-apply/cancel` 撤回、`GET /user/list` 兼作审核队列（含 `roleAppliedAt`/`roleApplyNote`）、
  站长在账号页「成员管理」一键通过/驳回。前端：账号页权限面板三态（可申请 / 审核中可撤回 / 已是作者）
  + 待审计数 + 通过驳回按钮，两处都提示「通过后需重新登录才生效」。
- 头像已完成（图片 + 底字双轨 + 可切换对象存储）：`user.avatar_url` + `POST/DELETE /user/avatar`
  （multipart 上传，服务端改名 + 魔数校验 + 1MB 双拦）；存储有 `local`（本地磁盘 + `/uploads/**`
  匿名读路由 + Docker 卷）与 `cos`（腾讯云对象存储）两种，由 `stellar.ink.storage.type` 切换。
  前端新增 `components/common/UserAvatar.vue`（降级链路：图片 → 底字 → 昵称首字 → 星），
  接入**导航身份入口、文章/笔记作者署名 AuthorBadge（`/user/authors` 已带 `avatarUrl`）、
  账号页「我的星籍」（可上传/更换/恢复底字）**；
  账号页可单独保存 `avatarText` 底字，未上传图片时全站显示底字。
  一期边界：无缩略图/CDN/对象存储迁移脚本、无历史头像保留。
- 搜索与分页已完成：前端 `/search` 聚合文章 `/search` 与公开笔记 `/notes?keyword=`，按类型分区并各自分页；
  星图、公开笔记、我的笔记、草稿恢复和流星均支持「继续加载」，作者摘要请求按 100 个 id 自动分批；
  后端所有分页入口统一要求 `page >= 1`、`1 <= size <= 100`。
- 友链审核闭环已完成：公开 `/links` 只返回已接入项，申请状态为待审核；ADMIN 在账号页通过
  `/links/pending` 查看队列，并以 `PUT /links/{id}/status` 通过或驳回（状态 `0/1/2`）。
  （`PUT /user/profile` 已接：账号页可改底字与头像，以及「恢复本机默认偏好」。）
- 作者申请二期候选：申请通过后的站内通知、申请被驳回时的原因回执、防刷频率限制。
- 技术笔记编辑器已完成：笔记编辑区换成 CodeMirror 6 的 Live Preview（`components/editor/MarkdownEditor.vue`）——
  光标行显示源码、其余行渲染；支持 `Ctrl+B/I/K/S`、Tab 缩进、撤销重做、Markdown 语法着色，
  列表渲染成圆点；工具栏含「标准章节 / 提示卡（`> [!NOTE]`）/ 代码块 / 列表」四个插入按钮。
  **只改笔记编辑器**，文章的「留白写作舱」保持原样。
- 导航与体验收口已完成（上一轮）：**删除了空转功能**——执笔页的「专注模式」（只藏导航、不影响写作）、
  `/bridge` 舰桥页（实时预览是静态假图、笔名/签名只写 localStorage 而全站读服务端、每日目标无消费方）、
  首页走马灯与写死的「第 128 夜」、光谱页 14px 彩条、星籍页的假档案段（坐标/职业/正在循环/今日摄入/大事记）；
  **登出与主题切换收进导航头像菜单**（此前登出要点头像→账号页→第三个面板）；
  新增 `views/notfound/NotFoundView.vue` 404 页（此前未知路径静默回首页）；
  `scrollBehavior` 恢复 `savedPosition`、`settings.persist()` 带上返回目标；
  写作页与笔记编辑页的**自动保存失败会显示「未保存 · 点此重试」**（此前失败被吞、页脚仍显示「已保存」）；
  三个列表页（流星/回声/星链）的错误提示补 `&& !items.length`，翻页失败不再清空整屏。
  **无障碍与色板**：`base.css` 增加全局 `:focus-visible` 与 `prefers-reduced-motion` 降级；
  `index.html` 内联脚本先落主题（消除刷新闪烁）并跟随系统 `prefers-color-scheme`；
  新增 `--on-primary`，夜色/暮色/破晓三主题的 `--ink-faint`、破晓的强调色全部调到 WCAG AA（≥4.5:1）。
- 原型退场与顶栏导航已完成（本轮）：**删除整个 `prototype/`**（21 个 HTML 重设计提案与 b12 定稿都已并入实现，
  从此以 `styles/tokens/variables.css` 的 Token + `components/common/TopNav.vue` 为唯一视觉基准，
  不再有第二份需要同步的 UI 描述）；左侧竖栏 `RailNav.vue` 换成 b12 式顶栏——
  顶部 sticky、未滚动透明、滚动 40px 后浮出底色与分隔线，品牌「✦ 星笺 STELLAR INK」在左、
  一级导航居中、寻星（放大镜）/执笔（主色按钮，仅作者）/头像菜单（账号设置 · 我的笔记 · 外观 · 登出）在右，
  窄屏折两行、导航横向滚动。**一级导航 11 → 6 项**：光谱并入星图（`/archive` 新增标签星座 chips，
  与年份叠加筛选画布与长卷）、复核并入我的笔记（`/notes/mine?view=review` 视图切换，URL 可分享）、
  星籍并入账号（星籍面板补「加入星笺」，编号统一 `NO.ST-0001`）；合并时顺手删掉星籍页的印章、
  「全站口径」（与首页写作脉搏重复）、格言与三个占位「信标」链接。三个旧路径保留 `redirect`，
  老书签不撞 404。布局侧：`.main` 去掉 96px 左侧留白、阅读进度条改贴视口顶并抬到顶栏之上、
  toast 下移到 78px、阅读目录吸顶改为 80px。页面标签（`ACCOUNT · 账号与星籍` 这类）统一挪到
  标题后面：`SectionHead` 新增 `kicker` 属性 + 全局 `.title-row`，深读页/404 等自绘标题的页面同样处理。
- 技术笔记二期候选：笔记 ↔ 文章互链、`/tags` 与 `/stats` 是否合并笔记标签、笔记内全文检索、
  笔记间反向链接；AI 自动打标签/关联推荐需先明确解锁。
- 前端结构化待办：**流星与回声合并**（流星是作者发射的碎片、回声是任何人投的漂流瓶，
  两页受众不同，合并前要先定是「同页分栏」还是「视图切换」）；其余合并项（复核并入我的笔记、
  星籍并入账号）已在本轮完成。
