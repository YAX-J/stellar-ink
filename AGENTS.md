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
├── stellar-ink-server/  后端：Spring Cloud Alibaba 微服务（已跑通）
│   ├── stellar-ink-common/            公共库（Result/JwtUtil/异常，零 Spring 依赖）
│   ├── stellar-ink-gateway/           网关 :8080（路由 + CORS + JWT 鉴权）
│   ├── stellar-ink-service-user/      用户服务 :8101（登录/资料，表 user）
│   ├── stellar-ink-service-post/      文章服务 :8102（文章/标签/搜索，表 post）
│   ├── stellar-ink-service-meteor/    流星服务 :8103（表 meteor）
│   ├── stellar-ink-service-echo/      回声服务 :8104（表 echo）
│   ├── stellar-ink-service-link/      星链服务 :8105（表 link）
│   ├── stellar-ink-service-stats/     统计服务 :8106（Feign 聚合，无库）
│   └── stellar-ink-ai-client/         预留目录，AI 功能暂不开发，未经用户明确要求不得动它
├── stellar-ink-ai/      Python AI 服务占位，暂不开发
├── tools/nacos/         Nacos Server 本体（gitignore，不入库）
├── docs/architecture/   微服务架构说明
├── docs/api/README.md   接口文档（改接口必须同步更新）
└── deploy/sql|scripts/  数据库初始化脚本 / 一键启动脚本
```

## 2. 常用命令与端口

```bash
# 前端（端口 5173）
cd stellar-ink-web && npm install && npm run dev      # 开发
npm run build                                          # 构建验证

# 后端（网关 8080 对外；Nacos 8848；服务 8101-8106）
cd tools/nacos/bin && startup.cmd -m standalone       # 1. 先起 Nacos
cd stellar-ink-server && mvn -DskipTests package       # 2. 构建
deploy\scripts\start-all.bat                           # 3. 一键起全部（或按模块手动 java -jar）
```

- 提交前必须验证：前端 `npm run build` 通过；后端 `mvn package` 通过，且启动后通过网关（:8080）curl 过改动到的接口。
- 测试中文请求体时，git-bash 的 curl 会以 GBK 发送导致 500，先把 body 写成 UTF-8 文件（或用 node fetch）。
- 后端改代码前先停对应进程，否则 Windows 下 jar 被锁定，`mvn package` 的 repackage 会失败。

## 3. 通用工程规范

- **Git**：功能走 `feature/*` 分支；提交信息格式 `type(范围): 中文主题`，正文用 `-` 列要点。
  常用 type：feat / fix / docs / chore / refactor。**只做用户要求的提交与推送**。
- **禁止入库**：`node_modules/`、`dist/`、`target/`、`.vite/`、`.idea/`、`tools/`（Nacos 本体）等（见根 .gitignore）。
- 新增依赖要克制：前端不加 UI 组件库；后端版本必须整体联动（见下），先在父 pom `dependencyManagement` 登记。
- 所有文本文件 UTF-8（Windows 下注意别让 IDE 存成 GBK）。
- 文档同步：改了接口/启动方式/目录结构，必须同步更新 `docs/api/README.md`、`docs/architecture/README.md` 和本文档。

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

## 5. 后端规范（stellar-ink-server，Spring Cloud Alibaba 微服务）

### 版本矩阵（必须整体联动升级，不可单点调整）
- Spring Boot 3.3.12 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.3（官方匹配组合）
- MyBatis-Plus 3.5.12（分页拦截器需额外引 mybatis-plus-jsqlparser）、jjwt 0.12.6

### 架构与边界
- 拓扑/端口/调用关系见 `docs/architecture/README.md`；对外唯一入口是网关 :8080，API 路径与前端约定保持稳定。
- 服务按业务域拆分，**表归属严格划分**：user→`user`、post→`post`、meteor→`meteor`、echo→`echo`、
  link→`link`；stats 无库（OpenFeign 聚合 post-service 的 `/internal/posts/summary`）。
- 共享库模式：一个 `stellar_ink` 库（兼容云数据库无建库权限），各服务**只读写自己的表**；
  拆库时改各服务的 `MYSQL_DB` 环境变量即可，无需改代码。
- **鉴权在网关**（AuthGlobalFilter）：放行 GET/OPTIONS、`/auth/**`、公开写接口
  （`POST /echos`、`POST /links`、`POST /posts/{id}/glow`）；其余对
  `/posts|/meteors|/links|/user` 的写请求校验 JWT 后注入 `X-User-Id`（剥离客户端伪造的同名头）。
  下游服务不校验 JWT，只读 `X-User-Id`，缺失视为绕过网关直接抛 401。
- 服务间调用用 OpenFeign；`/internal/**` 为服务间接口，网关不配路由，外部不可达。
- 跨服务 DTO 复制不共享：服务间契约模型定义在调用方（如 stats 的 PostSummary），字段与提供方对齐；
  提供方接口改动必须通知调用方同步。

### 工程约定
- 包结构：`com.stellarink.<service>/{controller,service,entity,mapper,dto,vo,config}`；
  启动类 `@MapperScan` 指向本服务 mapper 包。
- 公共库 `stellar-ink-common`（Result/ResultCode/BusinessException/JwtUtil/常量）**保持零 Spring 依赖**，
  需要注册为 Bean 的（JwtUtil）在各服务 `config/` 里 `@Bean` 包装。
- 所有对外与内部接口统一返回 `Result<T>`；`Result` 必须保持可被 Feign/Jackson 反序列化
  （@NoArgsConstructor + @Setter，曾有裸数组/不可反序列化踩坑）。
- 业务校验失败抛 `BusinessException`（ResultCode.NOT_FOUND 等），全局处理器同步 4xx/5xx HTTP 状态码。

### 数据库
- 表名小写单数，列 snake_case，主键 `BIGINT AUTO_INCREMENT`。
- dev：各服务内置 H2（`schema.sql` 含幂等种子，与前端 prototype 的 mock 对齐）；
  生产：`deploy/sql/01_schema.sql` + `02_init-data.sql`（幂等，无建库权限场景友好）。**两处结构改动必须同步**。
- 已知坑：`user` 是 H2 保留字，dev 数据源 URL 带 `NON_KEYWORDS=USER`，别删。

### 日志（slf4j）
- 一律 `@Slf4j`；关键业务动作 info，登录失败/未授权/业务异常 warn（不含敏感信息），未捕获 error。
- 每服务独立日志文件 `logs/stellar-ink-<服务>.log`（UTF-8，按天+20MB 滚动，留 14 天）；
  访问日志 logger 名 `API-ACCESS`（RequestLogFilter），业务代码不重复记路径。

### 安全
- 密码只存 BCrypt；JWT 密钥各服务与网关保持一致，生产用环境变量 `STELLAR_JWT_SECRET` 覆盖，
  代码里不得出现新的硬编码密钥。

## 6. 当前状态与边界（不要越界开发）

- 已完成：前端全部 10 页（mock 数据）；后端微服务化（网关 + 6 服务 + Nacos 注册，全链路已实测）。
- **暂不做**：AI 相关一切（ai-client、stellar-ink-ai）、注册与多用户、评论系统、文件上传、
  全文检索引擎（现用 LIKE）、熔断限流（Sentinel）、配置中心（nacos-config）——用户明确要求后再动。
- 下一步方向（用户提出再做）：前端 store 从 mock 切到网关接口（:8080，路径不变，CORS 网关已放开）。
