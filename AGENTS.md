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
│   │   ├── shared-model/           共享模型：Response/ErrorCode/异常/DTO/VO（含 ai 子包）
│   │   └── common-core/            基础设施：全局异常/TraceId/MP配置/健康检查/Redis工具
│   ├── gateway-nacos-sentinel/     网关 :8080（WebFlux：路由/CORS/Sa-Token 鉴权/Sentinel）
│   ├── user-service/   :8101       登录认证、站长资料（表 user）
│   ├── content-service/:8102       文章/流星/回声/星链/写作统计（按领域分包）
│   ├── stellar-ink-ai-client/      Java → Python 内部客户端契约（Feign/DTO/降级，M0-3）
│   └── ai-service/     :8107       对外 /ai/** 出口（鉴权复核/配额/审计，M0-4）
├── stellar-ink-ai/                 Python AI 编排服务 :8200（仅内网可达，M0-1）
├── tools/nacos/                    Nacos Server 本体（gitignore，不入库）
├── docs/architecture/              微服务架构说明
├── docs/api/README.md              接口文档（改接口必须同步更新）
├── docs/ai/                        AI：技术路线（README）/ 实施顺序（implementation-roadmap）/ 开发流程（development-workflow）
├── deploy/sql|scripts/             数据库初始化脚本 / 一键启动脚本
└── deploy/docker/                  生产 Docker Compose 部署（Nacos/MySQL/Redis/Qdrant + 网关/2 服务/前端 Nginx 整栈自洽）
```

## 2. 常用命令与端口

```bash
# 前端（端口 5173）
cd stellar-ink-web && npm install && npm run dev      # 开发
npm run check                                          # 验证：自检（差异/Copilot 采纳/SSE 切帧 + 部署前缀 + 模型库写反馈）+ vite build

# 后端（网关 8080 对外；Nacos 8848；服务 8101-8102；Python AI 8200）
cd tools/nacos/bin && startup.cmd -m standalone       # 1. 先起 Nacos（若 Nacos 在远端服务器，跳过这步并设 NACOS_ADDR）
cd stellar-ink-server && mvn -DskipTests package       # 2. 构建（Python 侧无需构建，uv 首次运行会建 .venv）
deploy\scripts\start-all.bat                           # 3. 一键起全部：Python(:8200) + 4 个 Java 服务
                                                       #    它会等健康检查、并回显 /ai/health 证明 Java→Python 通了；
                                                       #    stop-all.bat 对应地也会停 :8200
                                                       #    注：该脚本为本地私有文件（含服务器连接信息），已 gitignore 不入库

# 生产部署（境外 Linux 服务器 Docker Compose；Nacos/MySQL/Redis/Qdrant 全部由编排拉起）
cd deploy/docker && cp .env.example .env && vi .env    # 1. 填 MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD / SA_TOKEN_JWT_SECRET / GATEWAY_CORS_ORIGINS
docker compose up -d --build                           # 2. 构建 + 启动（步骤详见 deploy/docker/README.md）
                                                       #    ⚠️ 源站必须放境外：大陆源站 + 未备案域名会被按 SNI 重置入站 443，CF 报 525
```

- 提交前必须验证：前端 `npm run build` 通过；后端 `mvn package` 通过，且启动后通过网关（:8080）curl 过改动到的接口。
- 测试中文请求体时，git-bash 的 curl 会以 GBK 发送导致 500，先把 body 写成 UTF-8 文件（或用 node fetch）。
- 后端改代码前先停对应进程，否则 Windows 下 jar 被锁定，`mvn package` 的 repackage 会失败。

### 环境矩阵（开发 / 测试 / 生产）

| 环境 | 地址 | 用途 |
|---|---|---|
| 开发 dev | **127.0.0.1**（本机） | 本机 MySQL / Redis / Nacos，日常开发与自测 |
| 测试 test | **124.221.158.32** | 测试服务器（此前文档里写作「远端服务器」的就是它） |
| 生产 prod | **103.14.33.78** | 生产服务器，`deploy/docker` 整栈部署 |

- 三套环境的**数据是分开的**（本机库 ≠ 测试库 ≠ 生产库），但 **Nacos（配置 + 注册中心）
  目前「开发 = 测试」共用**：`application-dev.yml` 默认 `NACOS_ADDR=124.221.158.32:8848`
  + 命名空间 `f0350c82-…`，与测试环境同机同空间（生产是另一台 `103.14.33.78` + 命名空间 `140e3d39-…`）。
  ⚠️ 后果：本机启动的服务会**注册进测试环境的注册中心**。测试机一旦也跑起同名服务，
  网关 `lb://` 就会在「本机实例 / 测试机实例」之间轮询，而两边连的又不是同一个 MySQL ——
  表现为「接口时好时坏、数据对不上」，且日志里看不出异常。测试机目前只跑中间件（应用端口全未监听），
  所以这个坑还没被触发，但它是现行配置的必然结果。
- 换环境只改 `NACOS_ADDR` / `NACOS_NAMESPACE` / `MYSQL_HOST` / `REDIS_HOST` 等环境变量，不改代码。
- **三档 profile 与剩下的待做**：代码里已有 `dev` / `test` / `prod` 三档
  （`application-{dev,test,prod}.yml` + `nacos-application-{dev,test,prod}.yml`，每服务 8 件）。
  `test` 档面向「应用与中间件**同机**跑在测试机」：地址默认 `127.0.0.1`，
  `SA_TOKEN_JWT_SECRET` 与 `MYSQL_PASSWORD` 必须显式注入（⚠️「无默认值」**不等于** fail-fast，
  见 §5「安全」的实测结论），起法：`java -jar xxx.jar --spring.profiles.active=test`。
  ⚠️ **单测的 profile 名是 `unittest`**（`src/test/resources/application-unittest.yml`，H2 内存库）：
  `test` 已被「测试环境档」占用，两者同名会互相遮蔽（同名资源只取 classpath 里的第一个）**且不报错** ——
  表现是「单测莫名连上真库」或「测试机起来却用了 H2」。写测试一律 `@ActiveProfiles("unittest")`。
  待做 ①：本机起 `tools/nacos`，dev 指向 `127.0.0.1:8848`（现状 dev 仍连测试机 Nacos）；
  待做 ②：测试机建独立命名空间（test 档现与 dev 共用 `f0350c82-…`，只需改默认值一处）。
- 测试与生产的 Nacos 8848 / MySQL 3306 / Redis 6379 / Qdrant 6333 **只应绑定宿主机
  `127.0.0.1`**，本机经 SSH 隧道访问（隧道命令见 `deploy/docker/.env.example` 末节）。
  这套编排文件里已经这么写了，端口对公网开放属于部署环节走样，不是配置缺失。
- ⚠️ **prod 默认值四个服务不统一（配置债，被 compose 的 env 兜住）**：ai-service 的
  `application-prod.yml` 是 Nacos `127.0.0.1:8848` + 命名空间 `public` + JDBC 默认 `mysql`，
  而 content / user / gateway 是 `103.14.33.78:8848` + `140e3d39-…`（content/user 的 JDBC 默认
  `127.0.0.1`）。不走 compose 直接跑 jar 时，ai-service 会去连不存在的 Nacos。

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
- **`.bat` / `.cmd` 必须是 CRLF 换行**（`.gitattributes` 里的 `*.bat text eol=crlf` 是对已入库文件的兜底，
  但**不入库的本地脚本没人兜底**，写完必须自己转）。踩过一次：用编辑器写出的 `start-all.bat` 是纯 LF，
  跑到 `goto wait_loop` 报「系统找不到指定的批处理标签」——原因是 cmd 按**字节偏移**扫描标签，
  LF-only 会让偏移算错。现象看起来像「标签名写错了」，实际是换行符；
  而且 `call :label` 有时反而能过，**别据此以为没事**。
  改完 `.bat` 的验证办法：复制一份，把 `call :kill_port` 与 `start "si-…"` 两类行换成 `echo`，
  拿它跑一遍 —— 控制流（标签、计数器、汇总）全都能验到，又不会真的重启服务。
- 文档同步：改了接口/启动方式/目录结构，必须同步更新 `docs/api/README.md`、`docs/architecture/README.md` 和本文档。
  ⚠️ **`docs/*` 在 .gitignore 里是被忽略的**（安全审查报告等本地文档不入库），只放开了
  `docs/api`、`docs/architecture`、`docs/ai` 与 **`docs/status.md`**（它是功能侧真相源，
  AGENTS 与 `docs/ai/*` 都在链接它）。所以在 `docs/` 顶层**新增**文件默认不会进仓库 ——
  踩过一次：多轮「同步 docs/status.md」改了磁盘却没进任何提交，克隆出来的仓库里那些链接是死的。
  要么 `git add -f`，要么在 `.gitignore` 里显式收窄规则。

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
- 字体：Space Grotesk / Noto Serif SC / Noto Sans SC / JetBrains Mono，
  正文 300 字重、标题用衬线 900，等宽字体用于元数据。**分两路加载，不要合并成一路**：
  - 拉丁（Space Grotesk 400/500/700、JetBrains Mono 400/600）**自托管**：
    `public/fonts/*.woff2`（**4 片 / 82KB**：两个字族在 Google 上是变量字体，
    400/500/700 返回的是同一个文件，因此按「字族 × latin/latin-ext 子集」各留一份，
    再由 `src/styles/fonts.css` 按字重声明成多条 `@font-face`）+ `main.js` 里在 tokens 之前 import。
    别按字重各存一份文件（曾存成 10 片，多出 123KB 纯重复），也别把拉丁字族挂回 Google。
  - 中文（Noto Sans SC 300/400/500/700、Noto Serif SC 600/900）走 Google，
    单字重 4.4-5.9MB 不进仓库。**域名用 `fonts.googleapis.cn` / `fonts.gstatic.cn`**（Google 自家中国域名，
    CSS 与 .com 逐字节相同）：国内实测 0.25-0.28s vs .com 0.95-1.13s（偶发 20s 卡死），
    字体分片两边都是 0.12-0.16s。`index.html` 里 preconnect 与 link/noscript 三处必须同时改域名。
  - 系统 CJK 兜底写在 `variables.css` 的字体栈里（PingFang SC / Microsoft YaHei / 思源），
    中文字体没到之前先用系统字体渲染，不要为此再加字体。
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
- **「没给出答案」有三种形态，前端必须分开显示**：预算用尽（`doneReason=length`，**不是失败**，
  常常还带着引用）、用户主动停止、请求真的失败 —— 只有最后一种进 `error`。
  把前两种显示成失败，用户会以为东西没了，其实已经查到了。见「深挖」面板与
  `scripts/agent-selfcheck.mjs`（这三条是它的主要断言）。
- **界面不得承诺服务端不会兑现的数字**：Agent 的预算由 ai-service 用 `min(请求值, 默认)`
  夹住（4 步 / 6 次），所以前端**不能**写「深一点（6 步）」——传 6 只会跑 4。
  正确做法是「不带该字段，由服务端决定」，或只提供**收紧**档（3 步）。同理
  `POST /echos`、`/links` 这类限流接口的文案要与真实限流口径一致。
- **「停止」要真的中止请求**：`api/client.js` 的 `request()` 接受调用方的 `signal`；
  不接的话只是本地不再等，服务端照样跑完（Agent 会继续烧多次模型调用）。
  文案也要诚实：服务端只在**步与步之间**检查中断，所以是「已停止等待」而不是「已取消」。
- **写成功之后的刷新失败，不得把这次写显示成失败**（踩过一次「保存按钮是假的」）：POST 已返回 200
  就说明数据落库了，紧跟其后的 `loadModels()` 只是为了让页面好看 —— 它拿到的 503（模型库那条 GET
  过网关，而网关撤销校验 fail-closed）绝不能冒泡成「保存失败」。做法固定两条：
  ① **先就地更新本地状态**（列表里插入/替换/删除那条，并 `modelsLoaded = true`），
  ② 刷新走 best-effort（`stores/ai.js` 的 `refreshAfterWrite()`：失败只记 `modelsError`，不抛）。
  现象上「保存失败」+「表单不关」+「列表没变」+ 再点一次报「已经有同名模型」= 这个 bug，不是后端坏了。
  `scripts/ai-store-selfcheck.mjs` 把「刷新全 503 时保存/删除/绑定都不抛错且就地生效」变成 `npm run check` 的一部分。
- **不要用 `disabled` 挡表单校验，也不要让禁用态看不出来**：`.btn` 曾完全没有 `:disabled` 样式，
  禁用按钮和可点按钮长得一模一样、点下去又什么都不发生 —— 表现同样是「按钮是假的」。
  现在两条一起守：`components.css` 里 `.btn:disabled` 有可见差异（降透明 + `not-allowed`），
  保存类按钮**只在提交中禁用**，字段没填全时点得动并就地显示「还差：展示名、接口地址…」，
  提交失败的原因也留在按钮上方（`roleFormError` / `modelFormError`），不只靠会消失的全局 toast。

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
- 阅读偏好（字号 / 行距；**正文宽度已取消**，不要再加 `--read-w`）存 `stores/settings.js` 的 `read`，
  通过 CSS 变量 `--read-fs / --read-lh` 下发给正文，组件里不要硬编码字号。
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

### 配置文件风格（照参考工程，每个服务统一 8 件）
| 文件 | 内容 |
|---|---|
| `application.yml` | 极简：port + 应用名 + `profiles.active: dev` |
| `application-dev.yml` | `spring.config.import: optional:nacos:<app>-dev.yaml` + Nacos 配置/发现 + **Druid** 数据源 + sa-token + springdoc/knife4j + actuator 全暴露 + 日志降噪 |
| `application-prod.yml` | 生产：敏感项全走环境变量（`MYSQL_PASSWORD`、`SA_TOKEN_JWT_SECRET`、`NACOS_ADDR`） |
| `application-test.yml` | 测试环境（部署在测试机）：默认值面向「应用与中间件**同机**」（`NACOS_ADDR`/`MYSQL_HOST`/`REDIS_HOST` 全默认 `127.0.0.1`）；`SA_TOKEN_JWT_SECRET`、`MYSQL_PASSWORD` 必须显式注入（⚠️ 无默认值**不等于** fail-fast，见 §5「安全」）；actuator 保留 `metrics,loggers` 但高危端点显式关闭；**Redis 段显式写出来**（详见该文件头注释） |
| `nacos-application-dev.yml` | 上传 Nacos 的动态配置模板（Data ID：`<app>-dev.yaml`），放可调项 |
| `nacos-application-test.yml` | 测试环境模板（Data ID：`<app>-test.yaml`）。口径同 prod 模板：只放可调项、绝不放密钥 |
| `nacos-application-prod.yml` | 生产模板（Data ID：`<app>-prod.yaml`）。**只放可调项，绝不放密钥**（远端同名键**实测并不覆盖**本地 yml，见 `docs/architecture/README.md` §Nacos 动态配置；但「密钥不进配置中心」这条口径不变）。prod 是 `optional:` 导入，**不建也能启动** |
| `logback-spring.xml` | 控制台 + 异步文件 `./logs/<app>.log`（UTF-8，按天 + 100MB 滚动，保留 7 天，总上限 2G） |

- Nacos 地址统一用环境变量 `NACOS_ADDR`（默认 127.0.0.1:8848）、命名空间 `NACOS_NAMESPACE`
  （默认 public，config 与 discovery 必须同空间，3 个服务要一起设）；
  MySQL 用 `MYSQL_HOST/PORT/DB/USER/PASSWORD`；JWT 密钥用 `SA_TOKEN_JWT_SECRET`。
- **网络超时必须显式配**（本地跨公网连服务器时尤其重要：曾因链路抖动十几秒 + 默认无限等，
  把 `/tags` 挂到 23s）。约定：Redis `timeout` dev `500ms` / prod `1s`、`connect-timeout: 2s`
  （缓存是旁路，超时到点即回源；网关撤销检查 fail-closed，缩短超时只是更快暴露 503，不放行）；
  JDBC URL 必须带 `connectTimeout=3000&socketTimeout=15000`（Connector/J 默认 0 = 无限等，
  只能等操作系统放弃，Windows 约 21s）；Druid `max-wait: 5000`、`validation-query-timeout: 3`。
  改这些值时同步 `nacos-application-dev.yml` 模板，别只改本地文件。
- **跨公网连 MySQL 也必须保活**（同 Redis 那套，症状是「空闲一段后第一个 DB 请求卡 15 秒」）：
  MySQL 自己不掐连接（`wait_timeout`=28800），但 NAT 会丢空闲连接（实测 processlist 里
  34 条连接有 24 条 `Time` 上千秒、从没被 ping），空闲后第一个请求就借到半开连接，
  等 JDBC `socketTimeout`（15s）才失败换连接 —— 与浏览器 15s 超时重合。
  对策：`common-core` 的 **`DruidKeepAliveConfig`**（在**代码里**定死
  `keep-alive=true`、保活间隔、空闲门限、淘汰线程并打印生效值）+ **`DataSourceKeepAliveHeartbeat`**
  （每 30s **同时借出 initial-size 条**连接各跑一次 `SELECT 1`）。
  ⚠️ 只靠 Druid 自带的 `keep-alive` **不够**（实测：MySQL 侧一部分连接每十秒被 ping、
  另一部分闲置上千秒 —— 它只覆盖「超出 minIdle 的 + 最近使用的几条」，业务借到冷的那条照样卡 15s）；
  ⚠️ 心跳**必须同时借多条**：Druid 借用是 LIFO，借一条还一条只会反复热同一条；
  ⚠️ 远端 Nacos 也有同名键：实测它**并不覆盖**本地（见 `docs/architecture/README.md` §Nacos 动态配置），
  代码里定死仍是最稳的一层。
  验收：重启后 `SELECT time FROM information_schema.processlist WHERE host LIKE '<公网IP>%'`，
  不该有连接空闲超过 ~60 秒（注意排除**已 kill 的旧 JVM** 留下的僵尸连接 —— 它们空闲上千秒、
  但要按 id 与重启时间区分）。详见 `docs/architecture/README.md` §空闲保活。
  生产 Docker 里 MySQL 与应用同机同网，不存在这个问题。
- **重启后第一个请求慢 5 秒是 JCE 在校验 fat jar，不是数据库**：第一次用 HMAC 时 JVM 验「调用方所在 jar」
  的签名，嵌套 jar 下退化成逐条随机 `pread`；特征是公开接口快、第一个验签请求慢、并发请求同毫秒解开。
  对策是启动期预热（`JceWarmupRunner`，common-core 与网关各一份，**改一处同步另一处**），必须走生产入口
  `SaJwtUtil` 的签 + 验（⚠️ 只从自己类里调 `javax.crypto` 实测 0~12ms、等于没预热；且要用带 `timeout` 的重载）。
  详见 `docs/architecture/README.md` §重启后第一个请求为什么慢 5 秒。
- ⚠️ **「莫名其妙 503」在这条链路上有三个来源，先分清再动手**（用户反馈过，吃过一次亏）：
  ① 服务没启动 / 没注册进 Nacos（`Unable to find instance for xxx`）；
  ② **网关连不上 Redis** —— 撤销校验是 fail-closed，而撤销列表在**远端** Redis 上
  （与 MySQL / Nacos 同机；本机实测往返 ~36ms、命令超时 500ms，稳态余量充足）。
  真正会失败的是**连接被掐断、Lettuce 正在重连**的那一瞬间：这一个请求 503、下一个又好了，
  这就是「莫名其妙」的来源；
  ③ 下游服务自己返回 503。
  三种现在都能从响应体读出来（`GatewayErrorHandler` 与 `RevokedTokenFilter` 都给 `{code,msg,hint,path}`）。
  ② 的对策是 `RevokedTokenFilter` **重试一次**（间隔 120ms，两次都失败仍 fail-closed，
  安全口径不变）；四个服务必须连**同一个** Redis（user-service 写撤销键、网关读它）。
  ⚠️ **撤销校验还有一个 2s 的整体上限**（`SESSION_CHECK_TIMEOUT`）：命令超时只约束「命令」，
  **不约束从池里拿连接** —— 响应式池（`asyncPools`）的 `acquire()` 没有超时，
  池被占满或连接全卡死时它会永远不返回，网关一直挂着，用户只能等浏览器自己的 15s 超时
  （截图里那两个 `(canceled) @15s` 就是这么来的，日志与响应体什么都没留下）。
  有上限之后最坏情况是 2s 内回一个带 hint 的 503，前端能直接显示「哪一环断了」。
  ⚠️ **网关的 Redis 参数有第二份来源**：远端 Nacos 有 `gateway-nacos-sentinel-dev.yaml`（含 redis 段）。
  它此前因字符集问题整份没生效（启动脚本已加 `-Dfile.encoding=UTF-8`），而**实测其值仍不覆盖本地**
  `application-dev.yml` —— 调超时请两处都改，别只改一处（依据见 `docs/architecture/README.md` §Nacos 动态配置）。
  同理 `Nacos` 不可达时服务靠网关的实例缓存还能工作一会儿，重启后就集体 503。
- **Lettuce 三处加固（远端 Redis 必配）**：`common-core` 的 `RedisLettuceTuningConfig` +
  `RedisKeepAliveHeartbeat`，网关各有一份等价实现（WebFlux 不能依赖带 servlet 的 common-core），
  **改一处要同步另一处**：① 关共享原生连接（`shareNativeConnection=false`，只能由代码改）；
  ② 保活心跳（每 30s 借一条连接发**真 PING**，失败即 `resetConnection()` 丢整池）——
  ⚠️ **心跳必须打在实际被用的那个池上**：阻塞 `getConnection()` 与响应式 `getConnectionAsync()`
  是**两个独立的池**，网关的撤销校验走响应式，用错 API 会「日志正常但照样 503」；
  ⚠️ **`testWhileIdle`/`testOnBorrow` 没用**：Lettuce 的池化工厂只做 `isOpen()`（本地标志位）；
  ③ 池里只留一条空闲连接且不预造（`max-idle: 1` / `min-idle: 0`，LIFO 借用才拿得到被热过的那条）。
  完整排查过程与验收命令见 `docs/architecture/README.md` §空闲保活。

### 数据库
- 表名小写单数，列 snake_case，主键 `BIGINT AUTO_INCREMENT`；MySQL 8 / utf8mb4。
- DDL：`deploy/sql/01_schema.sql`（幂等）+ 种子 `02_init-data.sql`（与前端展示用的种子内容对齐）；
  已有库升级脚本按顺序各执行一次：`03_multi-author`（多作者归属）、
  `04_post_views_glow`（计数 + 点赞明细 + 浏览闸门）、`05_user_role`（早期库缺 `user.role`
  会让所有用户查询报 Unknown column）、`06_note`、`07_role_apply`、`08_user_avatar`、`09_comment`、
  `10_ai-schema`、`11_ai_model_library`、`12_ai_call_log`、`13_ai_wiki`、`14_ai_wiki_entity`
  （后五个是 AI 域的表/列）；
  各脚本改了什么见文件头注释。
- 作者申请口径：**不建独立申请表**，待审状态用 `user.role_applied_at` 非空表示（每人最多一条待审，
  最新即当前）；审核队列复用 `GET /user/list`，前端不再发第二个请求。
  **通过与驳回都复用 `PUT /user/{id}/role`**，并在 `changeRole` 内统一清空申请字段 ——
  不要新加「驳回」专用接口，否则「点通过」与「直接改角色」会出现两套代码路径与不一致状态。
- 头像口径：`avatar_url` 存**可直接给 `<img src>` 用的地址**（`local` 为站内相对路径
  `/uploads/avatars/u{userId}_{uuid8}.{ext}`；`cos` 为对象存储或 CDN 的绝对 URL）。上传
  `POST /user/avatar`（multipart，字段名 `file`）、删除 `DELETE /user/avatar`。
  **存储位置由 `stellar.ink.storage.type` 一个开关决定，两档：`local`（默认）/ `cos`**；
  业务层依赖 `storage/ObjectStorage` 接口，换存储=新增一个实现类，可随时回滚。
  - `local`：文件落 `UPLOAD_DIR`（生产 Docker 卷 `deploy/docker/data/uploads`，必须保留）；
    读取走 `/uploads/**` 独立网关路由（匿名可读）。**限制：文件与库必须同机可达**，
    本地连远程库或跑多实例时会出现「上传成功但图片 404」。
  - `cos`：腾讯云对象存储。`bucket` 必须带 APPID 后缀、`region` 形如 `ap-shanghai`；
    `public-base` 必须填**浏览器能访问到**的地址，填内网端点或 localhost 会导致全站图片 404。
    生产用 **COS 香港桶 + Cloudflare Worker 代理**暴露图片域名：CF 会把 Host 原样转给源站，
    而 COS 只认自己的端点域名，直接 CNAME 会 403/404；免费套餐既没有 Origin Rules，
    Transform Rules 又不许改 Host —— Worker 自己 fetch 天然带对的 Host，顺带吃到边缘缓存，
    配置与验证见 `deploy/cloudflare/README.md`。
    图片域名建议加 Cache Rule（Eligible + Edge/Browser TTL 1 年，
    对象名带随机串、换头像即换 URL，可长缓存）。详见 `docs/architecture/avatar-minio.md`。
  - 校验三件套收在 `storage/AvatarValidator`（服务端改名 + ImageIO 魔数认格式 + 1MB 双拦），
    两个实现共用，**对象存储实现不得绕过**；`ObjectStorage.delete` 必须尽力而为、
    只删自己前缀下的对象（切换存储后遗留的另一种形态 URL 要安全忽略）。
  - 密钥**刻意不作为配置项**，只从环境变量读取（见 `StorageProperties`）：COS 走 `COS_SECRET_ID` /
    `COS_SECRET_KEY`（生产用 CAM 子账号密钥并限定单桶，绝不用主账号密钥）。
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
- ⚠️ **`${SA_TOKEN_JWT_SECRET}` 这种「无默认值」写法 ≠ fail-fast（实测）**：未设置环境变量时
  Spring **不报错**，而是把这个**字面量字符串** `${SA_TOKEN_JWT_SECRET}` 当密钥传下去 ——
  服务照常启动、JCE 预热成功、`/actuator/env` 里能直接看到那个字面量值。
  也就是说：凡读到仓库的人都能用这个公开字符串自签一个带 `role` 的 token。
  **已落地的防线（2026-09）**：密钥规则收在 `shared-model` 的 `JwtSecretPolicy`（**唯一实现**，
  纯 Java 无 Spring），两个入口类共用它 —— 业务服务走 `common-core` 的 `SecretGuard`，
  网关走 `GatewaySecretGuard`（WebFlux 不能依赖 common-core，所以各留一个入口、规则只有一份）。
  行为：**dev 放行**（本机联调用仓库默认值），**其余档位一律校验**：空值 / 上面那个字面量 /
  仓库默认值 / 官方示例值 / 长度 < 32 全部**拒绝启动**；判据是 active profile 必须**全部**属于
  dev 系（`dev,prod` 这种混用也要校验）。
  ⚠️ 代价：**线上与测试机重启前必须确认 `SA_TOKEN_JWT_SECRET` 已真实注入**，否则网关会起不来 ——
  这正是想要的行为（拿公开字面量当密钥，比起不来危险得多）。
  改判定只改 `JwtSecretPolicy` 一处，两侧单测（`SecretGuardTest` / `GatewaySecretGuardTest`）同时盯住。

### AI 模块口径（M0 起建立，后续按里程碑扩展）
- **AI 能力一律写在 Python 侧（重要）**：模型调用与厂商 SDK、Prompt 与模板、结构化输出校验、
  切块、Embedding、向量检索、BM25/RRF、Rerank、引用组装、Agent 状态图与工具、记忆、GraphRAG、
  评测与 Trace，全部只在 `stellar-ink-ai` 实现。Java 侧（`ai-service` / `stellar-ink-ai-client`）
  **只做**鉴权与角色、配额与审计、协议转换（HTTP/SSE 事件格式）、超时取消降级、DTO/DTO 映射，
  **不得出现任何 AI 算法、模型调用或厂商 SDK 类型**（Provider 名称只作为配置值传递）。
  判断标准：一段代码如果「换成另一个模型/检索策略就要改」，它属于 Python。
- **端口与拓扑**：`ai-service :8107`（Java，对外 `/ai/**`）+ `stellar-ink-ai :8200`（Python，**仅内网**）。
  Python 不注册 Nacos、不配网关路由；浏览器与网关都不得直达 8200。
- **ai-service 只拥有 `ai_*` 表**：M0 时它以「排除数据源」表明「碰不到业务库」；A1 起需要
  `ai_provider_config`（模型配置）等 AI 域自己的表，因此恢复数据源与 MyBatis-Plus，但
  **Mapper 只允许 `com.stellarink.ai.**.mapper`、只访问 `ai_*` 表**，绝不读写 `user`/`post`。
  **E3-2 起 Redis 也放开了**（只放开 `RedisUtils`，`RedisCache` 仍排除）——配额计数与并发闸门要用；
  连接参数显式写在 `application-{dev,test,prod}.yml`（不靠 Nacos 下发：Spring 不认 `REDIS_HOST`，
  配置里没有占位符时会静默退回 localhost）。⚠️ 代价：`RedisUtils` 是**独立装配的 @Component**，
  ai-service 的每个 `@WebMvcTest` 切片都要 `@MockBean` 它，否则整个切片上下文起不来。
  不要用 `@MapperScan`（它会污染 `@WebMvcTest` 切片测试）；在 Mapper 接口上标 `@Mapper`。
- **AI 配额口径（E3-2）**：额度定义在配置（`stellar.ink.ai.quota.*`，可调项放 Nacos），
  计数在 Redis（键前缀 `stellar-ink:ai:quota:`，窗口是自然日）。三条必须保持：
  ① `0`/负数 = **不限**（默认不拦任何人，否则升级会让功能突然不可用）；
  ② 触顶返回 **429**（前端 `isRateLimited()` 认 status/code 双 429）；
  ③ Redis 不可用时 **fail-open + warn**（配额不是安全边界，安全边界才 fail-closed）。
- **MCP 口径（E3-3）**：`POST /mcp`（Python :8200，**不走网关**）是只读工具的标准协议面，
  工具集与 Agent **同一份**（`read_only_tools`）。三条必须保持：
  ① **它只是协议层**，不替代 Java 网关门槛、服务内复核与 `ToolBox` 白名单
  （只读仍是「装不进来」而不是运行期判断）；
  ② **身份只从签名的 `X-AI-*` 头来，schema 之外的参数一律 `-32602` 拒绝**
  （作者身份不进参数 —— 这是「客户端不能靠构造参数扩权」的落点）；
  ③ 错误语义照规范分三层：协议错误走 JSON-RPC `error`、权限不足 `-32003`、
  **工具执行失败是 `result.isError=true`**（客户端要喂回模型，不是协议故障）。
  新增工具时同时补 `input_schema` / `required_role` / `timeout_ms`（都在 `ToolSpec` 上）；
  新增路由要同步 `tests/test_app.py` 的 `EXPOSED_PATHS`（那条断言会直接红）。
- **Provider 层两条护栏（B/C 收口）**：① **退避重试**（`app/providers/retry.py`）——
  只重试错误分类里 `retryable=True` 的（429/5xx/超时/连不上），401/400 一次都不重试；
  退避确定性、有上限（`attempts` 与 `max_delay_ms`），上游给 `Retry-After` 就听它的（仍封顶）。
  ⚠️ **「额度用尽」与「瞬时限流」必须分开**（`ProviderQuotaExhaustedError`，`retryable=False`）：
  实测免费档是**每模型每日 50 次**（`limit_source=openrouter_free_tier_daily`，次日 UTC 零点重置），
  退避几秒救不了 —— 消息里必须带「每日上限 + 重置时间 + 重试无用」，
  否则「今天别试了」会被说成「稍后重试」，让人白折腾一天。对外错误码仍是 `AI_RATE_LIMITED`（不动契约）。
  ⚠️ 断言「状态码 → 错误分类」的测试要显式传 `RetryPolicy.disabled()`，
  否则每个失败态用例白等 5.6 秒（套件从 7 秒变 35 秒，已踩过）。
  ② **嵌入缓存**（`app/providers/embedding_cache.py`，在 `ProviderRegistry._build` 里包）——
  键含**模型指纹**（换模型必须重嵌，否则「链路全对、结果全错」）、有界 LRU、失败不缓存；
  **只包嵌入不包 chat**（对话有状态）。背景：不缓存时三个 dense 管道各嵌一遍整库，
  免费档直接 429，30 道题全被降级成「拒答」，看起来像「开了重排就彻底失效」。
- **LLM Wiki 口径（E4，逐段细节见 `docs/ai/status.md`）**：每条主张都绑定
  `postId` + `chunkIndex` + `postVersion` + `contentHash` + `quote`，且**引用必须被观察到**
  （`quote` 真的出现在它标注的段落里，规范化空白后比对），否则丢弃并按原因计数 ——
  这就是「事实性文本必须能回到证据」，也是它与「让模型写段摘要」的根本区别。
  四条不许放松的口径：
  ① **幂等锚点必须有**（主张 `(post_id, content_hash, claim_text)`、实体 `normalized`、
     关系 `(source,target)` 且**两端排序**、主题**成员签名**而不是主题名）—— 重复构建是常态，
     没锚点库会一天天膨胀、看起来「一直在产出新知识」；
  ② **只增不删的关联必须先清后写**（关系证据、主题成员/证据）：留着旧证据会让权重与证据条数对不上，
     那条边/那页就没法核对了；
  ③ **口径要分清**：`entities`/`relations` 是落库侧的数、`entityProposed`/`entityKept` 是模型侧的账；
     受众侧读取**公开**（要登录才能看证据就变成「信我」），构建是 ADMIN 且走调用账 `scene=wiki`；
  ④ **没命中/没内容不是失败**：失效盘点分 `current`/`stale`/`orphan`（只看段落哈希、缺失按 current、
     **盘点不自动重建**）；GraphRAG 没命中就说没落点、回退向量检索，**不拿弱相关的边充数**。
  ⚠️ 提示词里的 JSON 示例要放**单独常量**并用 `{example}` 注入（直接写进模板会被 `str.format`
  当字段名，报一个与真实原因毫不相干的 KeyError）。⚠️ 知识图的 Mapper 一律**顶层接口 + `@Mapper`**。
- **辅助信息失败，不得损伤主流程**（知识条目就是这么做的）：取不到时**整块不出现**、
  请求 `silent: true` 不弹提示 —— 网关抖一下不能让正文读不了。
  同时**「取不到」与「没有内容」必须是两个状态**（`failed` 与 `claims` 分开），
  混起来会让「服务坏了」看起来像「这篇文章没有知识条目」。
  这与「写成功之后的刷新失败不得把这次写显示成失败」是同一条口径的两面。
- **正文定位按文本找，不要按块下标找**：知识条目存的是段落哈希与原文片段，
  而正文渲染出来的 Markdown 块（段落/引用/列表/代码……）与切块用的子块**不是同一套下标**。
  按下标硬对，文章一编辑就会悄悄指错地方；按文本找最多是「找不到」——
  而找不到可以如实说出来（「正文里找不到这段文字 —— 文章可能在抽取之后改过」）。
  片段短于 4 个字符不定位：宁可不跳，也不跳错。样式要写在 `MarkdownBody.vue` 里
  （类名加在该组件渲染的元素上，父组件的 scoped 样式匹配不到）。
- **模型配置口径（重要）**：**面板是模型的唯一来源，代码里没有任何默认模型或厂商预设**。
  前端 `AiLabView` 不预置厂商、不带默认端点与模型名（端点与模型名照服务方文档填），
  Python 侧按角色（`chat` / `fast` / `reasoning` / `embedding` / `rerank`）从
  「`AI_PROVIDER_CONFIG_JSON` → `ai_provider_config` 表」读取，**空配置就报
  「角色 X 尚未配置模型（请在 AI 实验室 → 模型配置里填写）」并返回 400，绝不退回 Fake**——
  退回会让「忘了配」表现成「回答质量差」，是最难查的一类问题。
  `fake` 仍然可用，但必须**显式**配置（面板里把协议选成 fake，或测试里显式注入），
  它只用于离线自测与契约测试。
  **`base_url` 填 API 根，路径由代码拼**（`/chat/completions`、`/embeddings`、`/rerank`）：
  填成完整端点会拼出 `/rerank/rerank` → 404，而报出来的话是「模型名不存在」（踩过）。
  面板那个「测试连接」是 `scope: tcp_only`，对这类错误**一声不响**；
  验证「配置真的能用」要跑 `uv run python scripts/provider_smoke.py`（按角色各打一次真实调用）。
  面板 `POST /ai/admin/providers` 提交明文 Key，
  落库前 AES-256-GCM 加密（`AesGcmCipher`，主密钥 `AI_SECRET_MASTER_KEY` 只在环境变量），
  列表**只回掩码**（`sk-…9f3a`），没有任何接口能读回明文。加解密在 Java 与 Python 各实现一份，
  一致性由 `stellar-ink-ai/tests/fixtures/key_vector.json` 的固化向量守住（两侧单测都读它）。
  装配收在 `app/providers/runtime.py`（唯一解析器 + `require_roles` 预检）、
  `app/rag/corpus.py`（语料唯一缓存）与 `app/api/v1/assembly.py`（检索管道按
  「语料版本 + 开关 + 配置指纹」缓存）——**新端点必须走这三个模块**，
  不要自己 `FakeProvider()`、也不要自己 `lru_cache` 一份语料。
- **模型库与角色绑定（面板怎么用）**：`ai_provider_config` 是 `UNIQUE KEY uk_role`（一个角色一行），
  所以「再加一个 chat 模型」会覆盖原来那行。因此拆成两张表：
  `ai_model` 是**素材库**（`deploy/sql/11_ai_model_library.sql`，每个模型一行，各自标注能力），
  `ai_provider_config` 仍是**每个角色当前生效的配置**，只多一列 `model_id` 记住来源。
  **Python 读的还是角色表，因此不需要任何改动** —— 新增能力时别再往 Python 里加一张表。
  三条必须保持的约定：① 改库里的模型会由后端**同步**到所有绑定它的角色（否则「换了 Key 却不生效」）；
  ② 绑定前校验**能力匹配**（角色需要的能力见 `AiModelRole.capability()`，
  必须与 Python 的 `providers/registry.py::_ROLE_CAPABILITY` 一致）；③ 正被使用的模型不许直接删，`force=true` 只解绑。
  ⚠️ 涉及「把某列置空」的更新**必须**走 `AiProviderConfigMapper.updateWithModelId`：
  MyBatis-Plus 的 `updateById` 会忽略 null 字段，用它清 `model_id` 的表现是「接口成功、刷新又回来了」。
- **身份只由 Java 传**：Python 不解析 Sa-Token、不读写 `user`/`post`；`userId`/`role`/`traceId`
  经 `X-AI-*` 带时间戳签名头传入（常量在 `stellar-ink-ai-client` 的 `AiInternalHeaders`），
  签名与 nonce 防重放已在 A2 落地（Python 侧 nonce 目前是**进程内** + TTL，多实例前换 Redis）。
  密钥 `AI_INTERNAL_SECRET` 无默认值，缺失即拒绝启动相关能力。
- **Python 地址只有一个键**：`stellar.ink.ai.python-base-url`（即 `AI_PYTHON_BASE_URL`）。
  Feign（`PythonAiClient` 的 `@FeignClient(url=…)`）、SSE 客户端（`HttpQaStreamClient`）
  与探活（`AiProperties`）**必须读同一个键** —— 曾经有三处读 `ai.python.base-url`
  而这个键哪里都没定义，配置被静默忽略、永远走硬编码的默认地址：
  本地碰巧一致看不出来，Docker 里就是「探活说可用、功能全挂」。
- **Java ↔ Python 的错误契约**：Python 的错误体统一是 `{code, message}`（`message` 是**给人看的可操作提示**，
  例如「角色 X 尚未配置模型（请在 AI 实验室 → 模型配置里填写）」）。Java 侧由
  `PythonErrorDecoder`（全局 Bean，见 `PythonAiClientConfig`）把它翻成 `PythonApiException`，
  **消息一字不改地交给用户**；新加 Feign 方法不需要额外处理。
  ⚠️ 两个刻意的映射：Python 的 401/403 只可能来自内部签名校验，**不能**映射成
  `UNAUTHORIZED`（前端会据此清会话把用户踢出去）；429 映射成「服务不可用」但保留上游那句「稍后重试」。
  契约外的错误体（网关 HTML、FastAPI 的 `{detail}`）退回默认行为，**不得回显上游原文**。
  ⚠️ 这里**没有** Feign 降级工厂：曾经有一个 `PythonAiClientFallbackFactory`，但 ai-service 没有
  circuit breaker 依赖（Spring Cloud OpenFeign 会忽略 `fallbackFactory`），它从不生效、已删除。
  Python 不可用时就是异常穿透 → 全局处理器给 `code=500`，别把它误判成「服务本身坏了」。
- **内部签名的标准串（跨语言，改必须两侧同时改）**：
  `METHOD \n PATH \n TIMESTAMP_MS \n NONCE \n SHA256_HEX(BODY) \n USER_ID \n ROLE`；
  签名是 HMAC-SHA256 小写十六进制，放 `X-AI-Signature`。
  两端各一份实现（Java `CanonicalRequest`/`InternalRequestSigner`、Python `app/core/internal_auth.py`），
  **一致性由 `stellar-ink-ai/tests/fixtures/signature_vector.json` 的固定向量守住**（两侧单测都读它）。
  ⚠️ **身份字段必须参与签名**：只签 body 的话，内网中间人把 `X-AI-User-Id` 改成 1 就能冒充 ADMIN ——
  那是「验签通过但身份是别人」，比不验签更危险。同理角色只接受 `READER/AUTHOR/ADMIN` 白名单。
  时间窗 ±60s，nonce 在 TTL 内不得重复；校验顺序固定「时间戳 → 签名 → nonce」，
  nonce 放在最后是为了不让垃圾签名把 nonce 表刷满。
- **跨语言契约单一来源**：Java DTO（`stellar-ink-ai-client`）与 Python Pydantic（`stellar-ink-ai/app/schemas`）
  共用 `stellar-ink-ai/tests/fixtures/*.json`，两侧各有一组契约测试读**同一批文件**；
  JSON 键名一律驼峰，枚举序列化用小写字面量（Java 侧必须 `@JsonValue`，默认会写成大写）。
  改契约要同时改：Python 模型、fixture、Java DTO 与 `docs/api/README.md`。
- **M0 的诚实降级**：Python 链路未接线时 `/ai/health` 返回 `available=false` 并给出可读原因，
  不允许假装健康（探活是真去问 `GET /health`，不是恒定假信号）；
  Python 不可用时**不得返回空答案**，让前端能区分「没有依据」与「服务坏了」。
  ⚠️ 目前这条由「异常穿透 + 全局处理器」实现（表现为 `code=500`），
  而不是设计里写的「Feign 降级给 503」—— 本仓库没有可用的降级工厂（见上）。
- 红线详见 `docs/ai/development-workflow.md` §7；每轮开工先读该文件，收尾更新 roadmap 进度清单与 §9。

## 6. 当前状态与边界（不要越界开发）

**进度快照见 [`docs/status.md`](docs/status.md)（功能侧）与 [`docs/ai/status.md`](docs/ai/status.md)（AI 逐阶段核验）。**
本节只放**规则与边界**，不记流水 —— 往这里加「X 已完成」会把它顶到工作区指令的 64KB 上限而被截断，
排查过程与产物路径请写进对应专题文档。

- **未做的事（别当成已做）**：E1 / E2 / E3 / B-C 收口与 **E4（LLM Wiki 全十一段：带证据抽取 →
  落库 → 读者侧条目/实体/主题 → 增量失效）都已落地**。**现在只剩 E5 GraphRAG**：
  E5-1 的图检索核心（Local/Global Search）已落地，E5-2 要把它接成评测策略、
  与 dense/sparse/hybrid 在同一批跨文章问题上比 —— **证明收益才保留**，
  不因为它叫 GraphRAG 就默认更好（这一条是 fast-track-plan 的约定，也是它唯一的上线条件）。
  两件环境动作**由用户处理**（换付费/自建 embedding 与 rerank、开 Qdrant 隧道），
  之后跑 `scripts/calibrate_dense_score.py` 与 `scripts/qdrant_smoke.py` 收口；
  **E5-2 的对比同样要在这两件做完之后才有意义**（免费档每日 50 次连一轮评测都跑不完）。
  **观测出口的接受形态就是进程内回放**（已拍板不部署 OTel/Langfuse；
  跨副本查不到时返回 `found=false` 是**已知且被接受的限制**，不是缺陷，别再当成待办）。
- **必须等用户明确要求才动**：文件上传、全文检索引擎（现用 LIKE）、Redis 限流、Sentinel 规则持久化。
  ⚠️ 这条里的「Redis 限流」指**博客 API 的边缘限流**；**AI 域的调用配额已获用户明确放行**
  （2026-10-01），E3-2 可以放开 ai-service 的 Redis。
  ⚠️ **GraphRAG 已获准进入（E5，2026-10）**，但形态被限定为「**先当评测策略**」：
  E5-1 的检索核心可以在没有额度时先写、先单测；**E5-2 的对比必须有真实额度**，
  且结论只能是二者之一：证明收益（保留并接读者侧）或没证明（**删掉，不留半成品**）。
  多 Agent 与微调**仍未开始**，仍守「一轮一个可验证切片、一个主题一个提交」。
- **不要加回来的入口**：光谱→星图（`/archive` 标签星座）、复核→`/notes/mine?view=review`、
  星籍→账号（`/account`）；旧路径只留 `redirect`。一级导航固定 6 项，
  视觉基准只有 `styles/tokens/variables.css` + `components/common/TopNav.vue`（`prototype/` 已整体删除）。
- **结构化待办**：流星与回声合并（先定「同页分栏」还是「视图切换」）；作者申请二期（站内通知、
  驳回原因回执、防刷限频）；技术笔记二期（笔记↔文章互链、笔记标签是否并入 `/tags`、
  笔记内全文检索、反向链接）。
