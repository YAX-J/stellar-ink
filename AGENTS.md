# AGENTS.md — 星笺 STELLAR INK 编码约定

> 所有 Agent / 协作者在本仓库写代码前必须先读本文档；与本文冲突的旧代码不代表规范可以放松。
> 回复用户、写注释、写提交信息一律使用中文（代码标识符用英文）。

## 1. 项目是什么

「星笺 · STELLAR INK」：一个把文章比作星辰的夜间写作博客。设计基调是深色星空、
缓慢、诗意 —— **任何 UI 改动不得破坏这个气质**（不引入亮色系大色块、不用圆角/字体之外的花哨组件库）。

```
stellar-ink/
├── prototype/           高保真原型（单文件 HTML，UI 的唯一视觉基准）
├── stellar-ink-web/     前端：Vue 3 + Vite + Pinia + Vue Router（已跑通，尚未接后端）
├── stellar-ink-server/  后端：Spring Boot 3.5 多模块（基础功能已跑通）
│   └── stellar-ink-ai-client/  预留目录，AI 功能暂不开发，未经用户明确要求不得动它
├── stellar-ink-ai/      Python AI 服务占位，暂不开发
├── docs/api/README.md   后端接口文档（改接口必须同步更新）
└── deploy/              部署脚本占位（暂空）
```

## 2. 常用命令与端口

```bash
# 前端（端口 5173）
cd stellar-ink-web && npm install && npm run dev      # 开发
npm run build                                          # 构建验证

# 后端（端口 8080；dev 用 H2 内存库免装数据库，种子账号 stellar / stellar123）
cd stellar-ink-server && mvn -DskipTests package
java -jar stellar-ink-api/target/stellar-ink-api.jar                    # dev
java -jar ... --spring.profiles.active=mysql                            # 生产
```

- 提交前必须验证：前端 `npm run build` 通过；后端 `mvn package` 通过，且启动后用 curl 打过改动到的接口。
- 测试中文请求体时，git-bash 的 curl 会以 GBK 发送导致 500，先把 body 写成 UTF-8 文件再 `--data-binary @file`。

## 3. 通用工程规范

- **Git**：功能走 `feature/*` 分支；提交信息格式 `type(范围): 中文主题`，正文用 `-` 列要点。
  常用 type：feat / fix / docs / chore / refactor。**只做用户要求的提交与推送**。
- **禁止入库**：`node_modules/`、`dist/`、`target/`、`.vite/`、`.idea/`、`.DS_Store`（见根 .gitignore）。
- 新增依赖要克制：前端不加 UI 组件库；后端不引入未被 parent BOM 管理的大件，先在父 pom `dependencyManagement` 登记。
- 所有文本文件 UTF-8（Windows 下注意别让 IDE 存成 GBK）。
- 文档同步：改了接口/启动方式/目录结构，必须同步更新 `docs/api/README.md` 和本文档。

## 4. 前端规范（stellar-ink-web）

### 架构与数据流
- 目录职责固定：`views/<页面>/XxxView.vue`、`components/{canvas,common,post}/`、
  `stores/`（Pinia）、`api/mock.js`（演示数据唯一来源）、`composables/`、`utils/`、`styles/`。
- 组件**不直接请求后端**：数据一律进 store；将来接真实 API 时只改 store 的取数来源，不动视图。
- 路由在 `router/index.js` 统一注册，页面组件懒加载；新页面必须同时在导航（RailNav）登记。

### 样式（最重要）
- **颜色/字体/圆角/缓动一律用 `styles/tokens/variables.css` 的 CSS 变量**
  （`--primary/--amber/--teal/--rose/--ink*/--bg*/--line/--r-*/--ease-*`），禁止硬编码色值。
- 全站三主题（night/dusk/dawn）靠 `body[data-theme]` 切变量实现；新组件必须保证在
  破晓（浅色）主题下可读——即只用语义变量、不用固定深色。
- 共用元件类（.btn/.kicker/.section-head/.chip/.field/.side-card/.echo-form/.map-tip/.foot）
  放 `styles/components.css`；页面私有样式写各自 view 的 `<style scoped>`。
- 字体：Space Grotesk / Noto Serif SC / Noto Sans SC / JetBrains Mono（index.html 引入），
  正文 300 字重、标题用衬线 900，等宽字体用于元数据。

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

## 5. 后端规范（stellar-ink-server）

### 模块与依赖方向（禁止反向依赖）
```
api → service → dao → domain → common
```
- `common`：Result/ResultCode、BusinessException、JwtUtil、常量。**保持零 Spring 依赖**，
  需要注册为 Bean 的工具类在 api 层 `config/` 里用 `@Bean` 包装。
- `domain`：DTO（入参）/ VO（出参）/ 枚举，纯 POJO + Lombok，不放业务逻辑。
- `dao`：MyBatis-Plus 实体（`@TableName` + `IdType.AUTO`）与 Mapper（继承 BaseMapper，不写 XML）。
- `service`：接口 + Impl，接口在 `service/<域>/`；业务校验失败抛 `BusinessException`。
- `api`：Controller 只做参数接收与结果包装（薄），config / advice / interceptor / filter 在此层。

### 接口约定
- 统一响应 `Result<T>`：`{code, message, data}`，code=0 成功；4xx/5xx 同步设置 HTTP 状态码。
- **鉴权边界**（AuthInterceptor 登记）：`/posts/**`、`/meteors/**`、`/links/*/status`、`/user/profile`
  的非 GET 请求需要 JWT；公开写接口仅限：回声投瓶、友链申请、文章 glow。新增写接口必须想清楚放哪边。
- Controller 的 GET 用 `@RequestParam`，POST/PUT 用 `@RequestBody` DTO；字段校验失败/资源缺失
  抛 BusinessException（ResultCode.NOT_FOUND 等），不要返回 null 让前端猜。

### 日志（slf4j）
- 一律 `@Slf4j`；关键业务动作记 info（发射/更新/删除/投瓶/友链/登录/资料更新），
  登录失败、未授权写请求、业务异常记 warn（不含敏感信息如密码），
  未捕获异常 error 由全局处理器统一记。
- 每个请求的访问日志由 `RequestLogFilter`（logger 名 `API-ACCESS`）输出，业务代码不用重复记路径。
- 输出策略在 application.yml：控制台 + `logs/stellar-ink.log`（UTF-8，按天+20MB 滚动，留 14 天）；
  dev 下 `com.stellarink=debug`，生产 info。

### 数据库
- 表名小写单数（post/meteor/echo/link/user），列 snake_case，主键 `BIGINT AUTO_INCREMENT`。
- DDL：dev 用 `stellar-ink-api/src/main/resources/schema.sql`（必须同时兼容 H2 MySQL 模式与 MySQL 8：
  不用反引号、不写 ENGINE）；生产用 `deploy/sql/01_schema.sql`（MySQL 8 正式 DDL）+ `02_init-data.sql`（幂等种子）。
  **两处表结构改动必须同步**。种子数据内容必须与前端 prototype 的 mock 对齐。
- 已知坑：`user` 是 H2 保留字，dev 数据源 URL 带 `NON_KEYWORDS=USER`，别删。
- 文章标签逗号分隔存储（`splitTags/joinTags` 统一在 PostServiceImpl），字数 = 正文去空白字符数。

### 安全
- 密码只存 BCrypt；JWT 密钥生产环境用环境变量 `STELLAR_JWT_SECRET` 覆盖，代码里不得出现新硬编码密钥。

## 6. 当前状态与边界（不要越界开发）

- 已完成：前端全部 10 页（mock 数据）、后端基础接口（文章/标签/流星/回声/星链/统计/搜索/认证/资料）。
- **暂不做**：AI 相关一切（ai-client、stellar-ink-ai）、注册与多用户、评论系统、文件上传、
  全文检索引擎（现用 LIKE）。用户明确要求后再动。
- 下一步方向（用户提出再做）：前端 store 从 mock 切到后端接口（`/api` 前缀直连 8080，dev 需 Vite 代理或 CORS——后端 CORS 已放开）。
