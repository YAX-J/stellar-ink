# 星笺 AI 开发流程（人机协作）

> 三份文档的分工：`README.md` 讲**技术路线与原理**，`implementation-roadmap.md` 讲**每个阶段做什么**，
> 本文讲**怎么一轮一轮做出来** —— 节奏、分工、验证、提交、红线与决策门。
> **每轮开工前先读本文；收尾时更新 roadmap 顶部的进度清单和本文 §9。**
>
> **要「现在到底做到哪了」看 [`status.md`](status.md)**：逐阶段状态 + 可执行核验命令 +
> 已知缺口（未做的部分明确写成「未开始」，不写成「已完成」）。

## 1. 分工

| 角色 | 负责 |
|---|---|
| 用户 | 模型与 Embedding 选型、密钥、服务器与硬件决策、阶段验收（亲手跑一次真实路径）、`git push`、随时叫停 |
| AI | 按里程碑切片写代码 + 测试 + 文档、跑本地验证命令、按主题提交（**不 push**）、汇总证据（命令输出 / 指标 / trace）、到决策门停下来汇报 |

原则：**AI 不擅自扩大范围**。roadmap 没写的能力（多模态、微调、换向量库、文件上传、真正的多 Agent）
不提前做；需要新增依赖时先说明理由与体量，再等确认。

## 2. 每轮节奏（一个「开发单元」= 一个可独立验证的主题）

1. **认领切片**：从某个 M 里切出最小可验证主题（例：`M0-2 Java↔Python 契约`）。
2. **先契约后实现**：接口、JSON Schema、Pydantic 模型、Fake Adapter 先落地 —— 模型、Qdrant、Redis 都必须可替换。
3. **测试先行**：单测 + 契约 fixture（Java DTO 与 Python Schema **共用同一组 JSON**）。
4. **薄封装实现**：切块、召回、RRF、重排、引用自己写；LangGraph 等框架只在真需要状态机时引入。
5. **本地验证**：跑 §5 的对应命令，把真实输出贴进汇报（不写「应该没问题」）。
6. **文档同步**：接口 → `docs/api/README.md`；拓扑/端口 → `docs/architecture/README.md`；
   工程约定 → `AGENTS.md`；进度 → roadmap 顶部清单。
7. **提交**：一个主题一个提交，`type(ai|rag|agent|web): 中文主题` + `-` 要点正文；**不 push**。
8. **汇报与决策门**：改了什么 / 证据 / 风险 / 下一步；命中 §8 的门就停下来等确认，不自行进入下一阶段。

切片规模约定：**一次提交 ≲ 300 行改动，且能被一条命令验证**。宁可多切几刀，不要一次交一大坨。

## 3. 里程碑与完成定义（DoD）

| 阶段 | 完成定义（一句话） | 需要的用户输入 |
|---|---|---|
| M0 | 三个模块可构建、可测试，全程 Fake Adapter，无真实密钥 | 无 |
| M1 | `curl → 网关 → ai-service → Python` 走通一次模拟 SSE；伪造身份头、过期签名、nonce 重放都被拒 | 无 |
| M2 | Fake 与至少一个**真实 Provider** 跑通同一任务，业务层不出现厂商 SDK 类型 | **模型 API Key 与选型** |
| M3 | 公开文章提问能返回可定位的原文引用，无证据问题明确拒答，`Recall@5` 有基线 | Qdrant 用本地 Docker 还是服务器容器 |
| M4 | 混合检索 / 重排相对 Dense 基线有量化提升，否则回退该组件 | 评测时间与技术预算 |
| M5 | 深读页问答（流式 + 引用定位）与执笔页 Copilot（差异预览 + 手动接受）两条路径可演示 | 亲手验收两条演示路径 |
| M6 | 云端与本地兼容服务通过同一套 Provider Contract，并产出压测报告 | GPU / 服务器资源与许可证 |
| M7 | 只读单 Agent 有步数、Token、超时预算，Interrupt/Resume 不重复副作用 | — |
| M8 | 一个 traceId 可回放模型 / 检索 / 工具全链路，配额与降级有自动化测试 | 是否部署 Langfuse |
| M9 / M10 / M11 | 记忆、GraphRAG、多模态按**真实使用数据**决定是否开工 | 真实使用数据 |

M0–M5 未完成前，不并行开发 Agent、GraphRAG 与微调（roadmap §3 的硬约束）。

## 4. 环境与端口

| 组件 | 端口 | 说明 |
|---|---|---|
| 前端 dev | 5173 | `npm run dev`，`/ai/**` 由 Vite 代理到网关 |
| gateway-nacos-sentinel | 8080 | 对外唯一入口，新增 `/ai/** → lb://ai-service` |
| user-service / content-service | 8101 / 8102 | 现有服务不动 |
| **ai-service**（新） | 8107 | Java 安全与业务边界：鉴权复核、配额、审计、`Response<T>` |
| **stellar-ink-ai**（新） | 8200 | Python，**仅内网可达**，不解析 Sa-Token |
| Qdrant | 6333 | 本地用 Docker 起；生产复用服务器已有容器 |
| Redis / MySQL | 6379 / 3306 | 现有容器复用 |

环境变量清单见 roadmap §18。**本地密钥只放 `.env`（已 gitignore），不入库、不进日志与前端产物。**

### Python 侧本地配置：`stellar-ink-ai/.env`

```bash
cd stellar-ink-ai
cp .env.example .env      # Windows: copy .env.example .env
# 只需填 AI_INTERNAL_SECRET 与 AI_SECRET_MASTER_KEY
uv sync && uv run uvicorn app.main:app --host 127.0.0.1 --port 8200
```

| 变量 | 必填 | 作用 |
|---|---|---|
| `AI_INTERNAL_SECRET` | ✅ | 内部签名密钥，**与 ai-service 逐字一致**；缺失则所有受保护接口 401 |
| `AI_SECRET_MASTER_KEY` | ✅（当前暂未用到） | 解 `ai_provider_config` 的密文；接真实 Provider 后必需 |
| `AI_APP_ENV` / `AI_PORT` / `AI_SERVICE_NAME` / `AI_LOG_LEVEL` | 否 | 有默认值（dev / 8200 / stellar-ink-ai / INFO） |

**为什么非得显式 `load_dotenv`（踩过一次）**：`pydantic-settings` 只把 `.env` 里的键
喂给 `Settings` 的字段，**不会**放进 `os.environ`；而两个密钥是从 `os.environ` 直读的。
于是「密钥写进 `.env` 就能用」这件事**不会自动成立** —— `AI_PORT` 生效、密钥却报未配置，
现象像密钥填错了，排查方向会跑到 Java 侧去。现在 `app/core/config.py` 在 import 期
`load_dotenv`（默认 `override=False`，**真实环境变量优先于文件**，生产注入的不会被盖掉），
并由 `tests/test_config_env.py` 用子进程盯着「两条路都要通」。

## 5. 每轮的最低验证门槛

```bash
# Python（新增代码必须全绿）
cd stellar-ink-ai && ruff check . && mypy app && pytest

# Python（改了模型配置或检索链路时再加一步：对每个角色打一次**真实**调用）
#   面板的「测试连接」是 tcp_only，只证明端点可达 —— 配置错了它一声不响（实测踩过）
uv run python scripts/provider_smoke.py          # 3/3 通过才算「配好了」
uv run python scripts/compare_strategies.py --provider panel   # 真实模型上的策略对比

# Java（改到的模块必须能打包；契约相关要跑测试）
cd stellar-ink-server && mvn -DskipTests package && mvn test

# 前端（动前端时跑；check = 自检 + build）
cd stellar-ink-web && npm run check
```

**前端的可执行验证**：项目没有测试运行器（AGENTS.md §3 不加测试依赖），
因此把「能纯函数化的判断」都抽到 `src/utils/`，由 `scripts/diff-selfcheck.mjs` 用 node 直接跑断言，
`npm run check` = 自检 + `vite build`。目前已覆盖行级差异（LCS/折叠/退化）、Copilot 采纳动作映射、
SSE 切帧（半帧/心跳/坏帧）—— 都是**错了会静默出问题**的地方（采纳动作错一个就会抹掉作者正文）。
新的纯逻辑（解析、映射、裁剪）照此办理：抽函数 → 加断言，别把它埋在 `.vue` 里。

**代理前缀也要被检查（踩过一次）**：`/ai` 在 D 阶段做完问答与 Copilot 之后，
**既没进 `vite.config.js` 的 proxy、也没进 nginx 的 location** ——
dev 下被 Vite 当 history 路由回退到 index.html，生产同样回退 SPA，
表现是「请求成功但页面空白」，而所有单测与构建都是绿的。
现在 `scripts/deploy-selfcheck.mjs` 会核对「前端源码里出现过的接口前缀，必须在两处代理里都出现」，
并顺带检查 nginx 的 `/ai` 是否关掉了响应缓冲（SSE 逐帧到达的前提）。
新增接口前缀时同步它的 `KNOWN_PREFIXES`。

**store 的写反馈也要被检查（踩过一次「保存按钮是假的」）**：`.vue` 里的纯逻辑能抽就抽，
但**读写时序**抽不出来，它住在 store 里。用户报过一次「添加模型的保存按钮是假的」，
后端探针证明 POST/GET/DELETE 全是 200 —— 是 `saveModel` 在 POST 成功后又 `await this.loadModels()`，
而那条 GET 过网关、网关撤销校验 fail-closed，抖动时 503。于是一次刷新失败就把**已经落库**的保存
显示成「保存失败」（列表没变、表单不关、再点一次报「已经有同名模型」）。
`scripts/ai-store-selfcheck.mjs` 用 Vite 的 `ssrLoadModule` 加载**真实 store**，
把刷新接口全打成 503，断言保存/删除/绑定都不抛错且就地生效；`npm run check` 里跑。
判据是一句话：**写已经返回成功，之后的刷新失败不许把它变成失败**。
新增这类「写 + 刷新」的 store action 时照此办理（先就地改本地状态，再 best-effort 刷新）。

改到真实接口后，还要按顺序起服务再验一次：
`Nacos → 四个 Java 服务（含 ai-service）→ stellar-ink-ai → 前端`，
经网关（不是直连服务）跑通改动路径；中文请求体写成 UTF-8 文件或用 Node Fetch（git-bash 的 curl 会发 GBK）。
M0 期间网关还没有 `/ai/**` 路由，`/ai/health` 只能直连 `127.0.0.1:8107` 验证（M1-1 接上后再改走网关）。

## 6. 分支与提交

- 分支：`feature/ai-<主题>`（如 `feature/ai-m0-contract`）。
- 提交拆分参考 roadmap §19；每次提交同时包含对应测试。
- AI 负责 `git add` / `git commit`，**不执行 `git push`**；推送与合并由用户完成。
- 提交信息用中文要点，写清「为什么这么做」，而不是罗列文件名。

## 7. 红线（越界即返工）

1. **密钥**：`AI_INTERNAL_SECRET`、模型 Key 无默认值，缺失时相关能力拒绝启动；不出现在日志、Actuator、异常响应、前端产物中。
2. **身份**：Python 不解析 Sa-Token、不读写 `user`/`post` 等业务表；`userId`/`role`/`traceId` 只由 Java 经带时间戳的 HMAC 头传入，并校验时间窗与 nonce 防重放。
3. **数据**：草稿与私密内容默认不出内网、不进公共索引；检索强制 `status=published` 过滤；用户数据必须可端到端删除。
4. **写入**：第一版 Agent 工具全只读；文章写入继续走现有 `/posts/**` 并由用户确认，Python 不直接写业务表。
5. **降级**：Provider 失败、超时、取消都要有明确错误与降级路径，不静默换模型、不故障放行。
6. **接口**：不擅自改现有接口语义；新增 AI 接口先写进 `docs/api/README.md` 再实现。

## 8. 决策门

进入下一阶段前必须给出证据并回答「是」（原文见 roadmap §20）：

- **M3 → M4**：引用能稳定定位原文？有黄金集与无答案问题？索引增删幂等？
- **M4 → M5**：高级检索相对基线有量化提升？评测可重复？成本与延迟可接受？
- **M5 → M6/M7**：前端取消能终止下游生成？AI 不能绕过文章权限？错误/超时/降级路径完整？
- **M7 → M8**：Agent 有预算？Tool Schema、权限、参数都有服务端校验？Interrupt/Resume 不重复副作用？
- **M8 → M9/M10**：每次调用可追踪？用户数据可完整删除？已有真实数据证明需要记忆或知识图？

## 9. 当前切片：D 阶段（前端 AI 入口：问答 + Copilot）

**排序以 `fast-track-plan.md` 为准**（A 控制面 → B 检索内核 → C 评测台 → D 前端实验室 → E 扩展）；
本节只记「这一刀做到哪、下一步做什么」，完整落地记录见该文件的 §5。

已交付（分支 `feature/ai-m0-contract`，全部本地提交）：

| 切片 | 结果 |
|---|---|
| M0-1 … M0-5 | 契约与工程骨架（Python 骨架 / 契约 fixture / Feign 客户端 / `ai-service` / 文档） |
| A1-1 … A1-4 | `ai_*` 表 + AES-GCM 密钥加密、Provider 配置 CRUD 与脱敏、Python Provider 层、前端配置面板 |
| A2-1 … A2-3 | 网关 `/ai/**` 路由与角色门槛、内部 HMAC 签名（**标准串含身份字段**）、Python 纯 ASGI 验签 |
| B2 / B3a | 文章切块（父块+子块、锚点、幂等）、BM25 + RRF + Dense 余弦 + 混合开关 |
| B3b-1 | 检索管道 `app/rag/pipeline.py`：召回 → RRF → Rerank → post 级去重 → 空即拒答，开关即 `RetrievalConfig` |
| B1 | Qdrant 适配 `app/rag/qdrant_store.py`（薄 HTTP、幂等 point id、错误分类）+ 32 条协议测试；**待一次真实冒烟** |
| B3b-2 | 索引写路径 `app/rag/index_pipeline.py` + `RetrievalPipeline.dense_store`（Dense 可走向量库）+ 5 条离线端到端测试 |
| C1 / C2 / C3-1 | 指标层、黄金集 v1（30 题 + 证据自检）、策略对比运行器、纯 BM25 本地基线 + 四路对比脚本、**评测接口（`/eval/datasets`、`/eval/strategies`、`/eval/run`）** |
| C3-2（Java） | 客户端 5 个 DTO + 两份 fixture、`AiEvalController`（`/ai/admin/eval/datasets|strategies|run`）、`InternalSignatureFeignInterceptor`（Feign 统一签名） |
| C3-2（前端） | `/ai-lab` 增加「评测台」页签（`?tab=eval`）：选数据集 → 勾策略 → 跑 → 对比表 + 逐题下钻（默认只看问题题）+ `notes` 原文展示 |
| D1 | 问答编排 `app/rag/qa.py` + 内网 `POST /qa`：检索 → 引用 → 提示词 → 模型 → 拒答；引用只列送进模型的段落，无依据不调模型 |
| D2 | 问答入口：网关 `POST /ai/qa`（登录即可）+ 深读页「问星笺」面板（`stores/qa.js`），引用可点回原文；**非流式先交付，SSE 后做** |
| D3（后端） | Copilot 编排 `app/rag/writing.py` + 内网 `/writing/suggest` + 网关 `/ai/writing/suggest`（AUTHOR；只给候选不写正文；离线桩按格式回答） |
| D3（前端） | 执笔页侧栏 `components/ai/CopilotPanel.vue` + `stores/copilot.js`：6 个功能按钮 → 候选 → **行级差异预览**（`utils/diff.js` 手写 LCS）+ 逐条「采纳」；采纳动作由纯函数 `utils/copilot-action.js` 决定；**没有「自动应用」开关**；`npm run check` 跑 28 条前端自检 |
| D2s（Python） | 事件契约 `app/schemas/qa_stream.py`（`data: {json}`，类型在 JSON 里）+ `QaService.stream()`（与非流式共用检索/引用/拒答，无流式能力则退化为单个 delta）+ `/qa/stream`（生产者任务 + 队列：心跳、`finally: task.cancel()` 取消传播、`X-Accel-Buffering: no`）+ Provider 的 `stream_chat()` |
| D2s（Java） | `ai-service/stream/`（`QaSseFrame` 帧模型 + `HttpQaStreamClient` 用 JDK HttpClient 单独开一条流；**Feign 的完整 body 语义会把 SSE 退化**）+ `AiQaStreamController` 的 `/ai/qa/stream`（`ResponseBodyEmitter` 逐帧转发、失败发 `error` 帧、`IOException` 即关下游）；顺带修掉「方法不对返回 500」→ `ErrorCode.METHOD_NOT_ALLOWED` |
| D2s（前端） | `utils/sse.js`（手写切帧：**`EventSource` 只支持 GET**，而问答必须 POST）+ `stores/qa.js` 的 `askStream`（逐帧拼成与一次性回答同形状的 `answer`，缺 `done` 提示中断）+ 深读页流式渲染与「停止」；补掉 `/ai` 在 vite 与 nginx 两处都缺失的**部署缺口**，并用 `scripts/deploy-selfcheck.mjs` 把它变成 `npm run check` 的一部分。**D 阶段收口** |
| E1 | `app/rag/style.py`（字符级统计，**不引分词库**；字组只在反复出现 ≥3 次时给出，**绝不引用原句**）+ `app/api/v1/style.py`（种子语料按 `authorId` 取样，样本不足返回人话）+ 契约 `app/schemas/style.py` 与 fixture（由 `scripts/gen_style_fixture.py` 用固定样本生成）+ Java `/ai/writing/style`（**authorId 取登录身份**）+ 执笔页只读画像面板 |
| E2（核心） | `app/rag/agent.py`（决策协议 `{thought,tool,arguments}` / `{thought,final,citations}`；**三维预算**步数·调用次数·观察字符；引用必须被观察到；中断只在步间检查）+ `app/rag/agent_tools.py`（只读工具，`ToolBox` 在装配时拒绝写工具）+ 内网 `/agent/ask` 与 Java `/ai/agent/ask`（默认预算 4/6，**客户端只能收紧**）。**前端入口未接**；E3/E4 未开始 |
| 真实模型核验（2026-10-01） | `scripts/provider_smoke.py`（按角色各打一次真实调用，替代「只证明端点可达」的面板自检）+ `compare_strategies.py --provider panel`（真模型走同一条编排）；同一轮修掉三处「报错指向错误方向」：rerank 的 `base_url` 双拼路径（404 报成「模型名不存在」）、评测单题上游失败被静默降级成「拒答」（429 变成「策略全错」）、读库 1044 冒成 `code=500` |

> **切片测试的一个坑（C3-2 踩到，值得记住）**：`ai-service` 的启动类**显式声明了 `@ComponentScan`**，
> 而显式声明会让 Spring Boot 切片测试的类型排除过滤器失效 —— `@WebMvcTest` 实际会把
> `com.stellarink.ai` 下的组件全部装配。后果是：新增一个控制器就可能让**别人的切片**起不来
> （这次是 `AiEvalController` 需要 Feign 客户端，而 Web 切片里没有 Feign 自动配置，
> 报「No qualifying bean of type FeignClientFactory」）。
> 处理方式：切片里把外部依赖 `@MockBean` 掉（探活切片 mock `PythonAiClient`，评测切片再 mock
> `AiProviderConfigService`）。将来若把启动类的显式 `@ComponentScan` 收掉，这条可以一起简化。

> **流式接口的一个坑（D2s 踩到，Python 侧）**：`InternalAuthMiddleware` 读完请求体之后
> 原本一律返回 `http.disconnect`，**非流式接口一切正常，SSE 直接 500**
> （Starlette 报 "No response returned"）。原因是 `BaseHTTPMiddleware`（traceId 中间件）
> 在响应进入流式发送后会调用 `receive()` 等断开信号，拿到伪造的「已断开」就取消整个响应任务组，
> 而 `http.response.start` 还没发出去。**伪造断开等于自己掐断自己的流** ——
> 现在第二次起交回真实 receive，并且有一条断言专门盯着这个不变量
> （`test_internal_auth_wiring.py::test_replay_hands_the_real_receive_back_after_the_body`）。
> 一般化的教训：**任何「自己造 receive/send 桩」的中间件都要先想一遍流式响应**，
> 因为流式路径下框架真的会去调 `receive()` 等断开。

> **测试断言的语义会随路由落地而改变（D2s 踩到）**：`AiHealthControllerTest` 原先拿
> `GET /ai/qa/stream` 当「未知路径」的代表；等这个接口真的实现出来，同一条断言的语义
> 就从「路径不存在」变成「方法不对」，而它报的是 **500**（方法不支持落进了兜底处理器）。
> 现在拆成两条互不依赖的断言：不存在的路径给 404，存在但方法不对给 405。
> 教训不是「别这么写测试」，而是：**一个接口的实现会让别人的断言悄悄换意思** ——
> 新增路由后要顺手看一眼有没有测试在拿它当反例。

> **流式响应下的 MockMvc 有盲区**：`ResponseBodyEmitter.send(..., TEXT_EVENT_STREAM)` 写的
> Content-Type **不会**出现在 `MockHttpServletResponse` 里（实测为 null），异步派发也一样。
> 所以流式切片里不要断言 Content-Type —— 那只会得到假警报；改为把
> `@PostMapping(produces = TEXT_EVENT_STREAM_VALUE)` 用反射钉住，
> 真正的端到端验证留给「起服务后用 curl -N」。

> M1 原计划的四刀（网关化 / 签名 / 验签 / 首次真实调用）已在 A2 完成，编号不再单独使用；
> M1-5（SSE）与 D2 一起做，避免先造一条没有消费方的流式通道。
> nonce 防重放目前是**进程内**存储 + TTL（见 `app/core/internal_auth.py`）；多实例部署前要换 Redis，
> 届时同时放开 `AiServiceApplication` 里对 `RedisUtils`/`RedisCache` 的排除，并同步 `AGENTS.md` 的 AI 口径。

**下一刀**：**真实模型链路的收口**（2026-10-01 已完成一半）——
`chat` / `embedding` / `rerank` 三个角色都已实测可用（`scripts/provider_smoke.py` 3/3），
真实嵌入下的 `dense` / `hybrid` 数字也拿到了（见 `status.md` §3）。剩下的两件：

1. **`minDenseScore` 标定** —— Dense 通路的拒答只能靠这个余弦绝对下限，而它必须按真实分数分布定。
   ⚠️ `calibrate_min_score.py` 扫的是 **BM25 的 `minScore`**（稀疏路），Dense 门限目前**没有入口**，
   得先补一个「按真实分数分布扫 `minDenseScore`」的工具，别拿稀疏路的曲线去定向量路的门限。
2. **免费档吞吐** —— 标准五组在 `:free` 模型下会 429（实测 `errorCount=30`，
   且评测里每个 dense 策略各自把整库嵌入一遍）。要跑完一轮，先做嵌入缓存与 429 退避，
   或者换一个不限额的嵌入/重排服务。

E3（MCP 与观测）、E4（GraphRAG / LLM Wiki）**未开始**，不要把它们说成「已完成」。
另外欠一次 Qdrant 真实冒烟（方式见下），做完才能说 B 阶段「实测通过」。

**E3 的第二刀已交付（2026-10-01 下午）**：E3-2 **配额与并发**——
用户（每日调用数 + token）、角色（每日调用数）、并发三维**调用前**拦截，触顶 429；
额度在配置（`stellar.ink.ai.quota.*`）、计数在 Redis（自然日窗口）；Redis 故障 fail-open 并告警。
⚠️ 这一刀放开了 ai-service 的 `RedisUtils`：**每个 `@WebMvcTest` 切片都要 `@MockBean` 它**，
否则切片上下文起不来（新增需要 Redis 的组件时同样如此）。

**B/C 收口第一刀已交付（2026-10-01）**：`app/providers/retry.py`（退避重试：只重试 `retryable` 的，
次数与等待都有上限；上游给 `Retry-After` 就听它的，仍封顶）+ `app/providers/embedding_cache.py`
（嵌入缓存：键含模型指纹、有界 LRU、失败不缓存；在 `ProviderRegistry._build` 里包，
整库嵌入从「每个管道一遍」降到「全局一遍」）+ **额度用尽与瞬时限流分开**
（`ProviderQuotaExhaustedError`：不重试，消息里带「每日上限 + 重置时间 + 重试无用」）。

⚠️ **重跑标准五组仍未通过，但真因已查清**：免费档是**每模型每日 50 次**
（`limit_source=openrouter_free_tier_daily`，`Remaining: 0`，次日 UTC 零点重置）。
这不靠代码解决 —— 需要给 embedding / rerank 换付费或自建模型（或等重置）。
**`minDenseScore` 标定必须先有真实分数分布，同样等这条腿可用再做**（不要拿伪向量凑门限）。
下一步按顺序：**E2 只读 Agent 前端入口**（离线可做）→ E4 GraphRAG / LLM Wiki；
Qdrant 冒烟只在隧道可达时做。
⚠️ **待你拍板**：是否部署 OpenTelemetry / Langfuse（决定跨副本回放与长期留存怎么做）；E4 的范围。

```bash
ssh -N -L 6333:127.0.0.1:6333 <server>          # 隧道（命令细节见 deploy/docker/README.md 第十节）
cd stellar-ink-ai && uv run python scripts/qdrant_smoke.py
```

冒烟用独立临时集合 `stellar_ink_smoke`（结束即删），不会碰生产集合。

### 只有一条检索编排（重要的工程决定）

`RetrievalPipeline` 是**评测台与业务链路共用的唯一编排**：基线脚本、四路对比、
将来的问答入口都只是「同一段代码 + 不同开关」。这样做的代价是这一层必须保持通用；
收益是「评测出来的数字」与「线上实际行为」不会因为两套实现而分叉 ——
历史上最容易出错的正是这种分叉（基线用 A 逻辑、线上用 B 逻辑，然后拿 A 的数字下结论）。

### 本地基线：为什么它是 C 阶段的地基

`uv run python scripts/eval_local_baseline.py` 不需要 Qdrant、不需要任何 API Key，
就能把「切块 → BM25 → 指标 → 对比表」整条链路跑一遍（当前：29 篇文章 / 41 个子块，
Recall@1 0.833、Recall@3 0.942、Precision@5 0.800、拒答率 0.4、误拒率 0.0）。
它的价值不是这些数字好看，而是**后面每一层高级能力都有了对照物**：
`scripts/compare_strategies.py` 已经把 sparse / dense / hybrid / hybrid+rerank 并排跑出来了 ——
其中 dense 接近随机（Fake 是哈希伪向量，无语义），这恰好证明向量通路真的在起作用；
而 hybrid+rerank 与 dense 完全相同，说明**重排必须换一个模型**才有意义。

两条实测结论（M1 起沿用，避免重复试错）：

- **解析器不能「少几条也不报错」**：种子解析器曾只读第一个 `post` 块、只认带引号的时间戳，
  静默丢掉 13–15 号短文，评测语料少三篇却毫无提示 —— 召回率偏低而没人怀疑语料。
  现在按行扫全部块，并用「独立数一遍行数」的测试盯着。
- **门限不能靠相对比例**：`min_score_ratio` 永远让最高分过线，**永远不会让结果为空**，
  拒答率恒为 0；只有绝对下限 `min_score` 能触发拒答。而两个分数分布重叠
  （有答案题最低 ≈ 14.3，无答案题最高 ≈ 24.1），所以拒答要靠主题相关性判定或
  Dense 相似度下限，而不是继续拧 BM25 门限。

另一条来自管道的接线经验：**开关必须能被测试证明「真的改变了行为」**。
管道用受控向量与计数桩验证「单路 Sparse 不花嵌入调用」「改权重能换掉第一名」
「重排下标越界/重复必须报错」；对比脚本则用「是否出现指标完全相同的配置对」来抓「开关没接上」。

### M0 期间的实测经验（M1 起沿用）

- **macOS/Windows 编码坑**：MockMvc 默认用 ISO-8859-1 解码响应体，中文断言会莫名其妙失败，
  必须 `getContentAsString(StandardCharsets.UTF_8)`；Python 写入的模板文件带 BOM 会让 `javac`
  报「非法字符 '\ufeff'」。
- **契约测试的假红灯**：比较 JSON 时不要直接比 `Map`/`JsonNode` 的数字节点（`12` 可能是
  Integer 或 Long、时间可能是 `+08:00` 或 `Z`，同一时刻字面量不同）——
  统一按 Long 读无类型整数，并把 ISO 时间归一到 UTC 瞬时再比。
- **公开接口只给结论**：`/ai/health` 的 `reason` 是「下游 AI 编排服务未就绪」，
  真实地址与异常留在服务端日志；字段白名单有测试守着，新增字段会被测试拦下。

### 迁移脚本编号提醒（沿用）

`deploy/sql/` 的 `01`–`09` 已被现有功能占用，AI 相关脚本从 **`10_ai-schema.sql`** 起编号
（现有：`10_ai-schema`（provider 配置 + 评测表骨架）、`11_ai_model_library`（模型库 + `model_id`）、
`12_ai_call_log`（调用账 + 角色单价两列））。用 `10`/`11` 时注意 `implementation-roadmap.md` §17
里的 `04/05` 是旧编号，已修正。**新增 AI 表时同时更新 `ai-service/src/test/resources/test-schema.sql`**
（H2 那份是测试用的镜像，漏了它整上下文测试会以「表不存在」的形态红）。

## 10. 待确认的选型（不阻塞 M0，M2 之前必须定）

| 项 | 候选 | 影响 |
|---|---|---|
| Chat 模型 | DeepSeek（chat / reasoner）、通义千问、OpenAI 兼容自建 | M2 的 Provider 默认配置 |
| Embedding | bge-m3（本地或云）、通义 text-embedding-v3 | 向量维度与索引结构，M3 前定死 |
| Reranker | bge-reranker-v2-m3 等 | M4 的重排链路 |
| Qdrant | 本地 Docker 自建 / 复用服务器容器 | 开发与生产的地址与密钥 |
| Python 包管理 | `uv`（推荐）或 `pip + venv` | 本地与 Docker 构建方式 |
| 服务器 GPU | 有 / 无 | M6 走 vLLM/SGLang 私有化，还是只做云模型适配 |
