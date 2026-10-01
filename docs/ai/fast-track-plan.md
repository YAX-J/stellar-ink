# 星笺 AI 实施路线（2026-09-24 修订：检索与评测主线优先）

> 这份文档**取代** `implementation-roadmap.md` 的 M1–M11 顺序，作为后续开工的准绳。
> `implementation-roadmap.md` 仍保留每个里程碑的**技术内容与验收口径**（能力清单、指标、红线），
> 但「先做哪个、以什么形态交付」以本文为准。
>
> 修订原因（用户 2026-09-24 的三条要求）：
> 1. 模型供应商要**配置驱动**，用户只填 API Key，不改代码就能换模型；
> 2. 评测工具（召回率、检索质量等）要**先建**，不能等最后一期；
> 3. 这些能力要能**在前端直接操作**：每个功能有按钮、每种检索策略可直接对比测试。

## 0. 与旧计划的关系

| 旧里程碑 | 旧交付形态 | 新计划 |
|---|---|---|
| M1 调用链 | 命令行走通一次模拟 SSE | **提前并为 A 阶段的底座**：网关路由 + HMAC + 配置中心 |
| M2 多模型网关 | 代码里配 4 个逻辑模型 | **A1 配置中心**：前端选模型、填 Key、存库、运行时读取 |
| M3 Dense RAG | 先只有 Dense | **B 阶段**：直接上「Dense + Sparse(BM25) + RRF + Rerank」可切换 |
| M4 Advanced RAG 与评测 | 评测是 CLI | **C 阶段**：评测台进前端，召回率/命中率/MRR/NDCG 直接看 |
| M5 前端问答与 Copilot | 深读页问答 + 执笔页建议 | **D 阶段**：先做「AI 实验室」页（调参与对比），再做业务入口 |
| M7 Agent / M8 MCP / M9 记忆 / M10 GraphRAG | 依次 | **E 阶段**：在主线上按需叠加，工具全部只读 |

不变的红线（`development-workflow.md` §7 全部继续有效）：密钥不入库明文、Python 不解析 Sa-Token、
不读 `post`/`user`、草稿不进公共索引、写入必须人工确认、失败要有明确降级。

## 1. 新的阶段划分

```text
A. 控制面与配置（先做，无向量库依赖）
   A1 模型配置中心：前端选 provider / 填 Key / 存库（加密）/ 连通性自检
   A2 网关与安全：/ai/** 路由、角色门槛、HMAC 内网签名、traceId 贯穿

B. 检索内核（依赖 Qdrant）
   B1 Qdrant 适配 + 集合与 payload 契约 + 健康检查
   B2 索引管道：文章 → 切块（父子）→ 嵌入 → 入库（幂等、可重建）
   B3 混合检索：Dense / Sparse(BM25) / RRF 融合 / Rerank，**每层可开关**

C. 评测台（依赖 B；这是用户要的重点）
   C1 黄金集与指标：Recall@K / Precision@K / MRR / NDCG / 命中率 / 拒答率 / 延迟
   C2 策略对比运行器：一次跑多组配置（单路 vs 多路、开/关重排），输出对比表
   C3 前端评测面板：选数据集 → 选配置 → 跑 → 看表格与逐题明细

D. 前端 AI 实验室 + 业务入口
   D1 问答编排 ✅（非流式）：检索 → 引用 → 提示词 → 模型 → 拒答，Python 内网 `POST /qa`
   D2 问答入口 ✅（深读页「问星笺」，经网关 `POST /ai/qa`，引用可点回原文）
   D2s 流式升级（SSE）+ 「浏览器断开即终止下游」：Python 侧 ✅、Java 出口 ✅、
   前端消费方 ✅（`utils/sse.js` 切帧 + `stores/qa.js` 逐帧拼装 + 深读页流式渲染与「停止」）。
   **D 阶段到此收口**（问答 SSE 与 Copilot 都已可用；真实模型接入仍待 Provider 配置下发）
   D3 Copilot：编排 ✅（`app/rag/writing.py` + 内网 `/writing/suggest` + 网关 `/ai/writing/suggest`，
      AUTHOR 门槛、只给候选不写正文）／前端差异预览面板 ✅（执笔页侧栏，**采纳必须人工点**）

E. 扩展（按需）
   E1 写作记忆与风格画像 ✅（只读统计量：句长/标点/关联词/反复字组；**不含原句**；
      Python 现算不落库 + `/ai/writing/style` + 执笔页只读面板）
   E2 只读单 Agent ✅ 核心（状态机 + 三维预算 + 中断 + 引用核实；工具只读且**装不进来**；
      内网 `/agent/ask` + 网关 `/ai/agent/ask`；**前端入口未接**，真实模型与配额待办）
   E3 MCP 工具服务与观测（配额、审计、成本看板）⏳ 进行中
      E3-1 调用账 ✅（`ai_call_log` + 五条路径埋点 + `/ai/admin/usage/summary`；
        身份只在 Java、表归 ai-service，成本按角色单价快照，缺口计数如实暴露）
      E3-2 配额与并发 ✅（用户·角色·并发三维；额度在配置、计数在 Redis；触顶 429；
        Redis 故障 fail-open 并告警）
      E3-3 MCP 工具服务 ✅（`POST /mcp`，JSON-RPC 2.0；工具集与 Agent 同一份；
        工具带 schema / 权限标签 / 超时 / 幂等；schema 外参数拒绝、身份只从签名头来）
      E3-4 观测出口 ✅ 最小形态（Python：进程内 trace 缓冲 + `/internal/trace/{id}`，
        只存结构不存内容、有界；Java：`GET /ai/admin/trace/{id}` 合并调用账与事件，
        Python 不可用时仍回账。跨副本/长期留存要 OTel 或 Langfuse → **待拍板**）
      E2 只读 Agent 前端入口 ✅（阅读页「问星笺」面板的模式切换：一次问答 / 深挖；
        深挖显示每一步的工具与标签；三种「没给出答案」的形态分开显示；
        `scripts/agent-selfcheck.mjs` 并入 `npm run check`）
      B/C 收口 ✅ 除两项环境动作：退避重试 + 嵌入缓存 + 额度识别 + minDenseScore 标定工具
        （标定要真实嵌入，等额度；Qdrant 冒烟要先开隧道）
   E4 GraphRAG / LLM Wiki ⏳ **进行中（先做 LLM Wiki）**，2026-10-01 用户拍板。
      E4-1 带证据的主张抽取 ✅（`app/rag/wiki.py` + `POST /wiki/claims`：原子主张绑定
        postId / 段落序号 / 文章版本 / 段落哈希 / 原文片段，**引用必须真的出现在那一段里**，
        编造的引用直接丢弃并按原因计数 —— 这就是验收口径「事实性文本必须能回到证据」的落地）
      E4-2 落库与读者侧 ✅（`deploy/sql/13_ai_wiki.sql` 的 `ai_wiki_claim`，幂等锚点
        = 文章 + 段落哈希 + 主张文本；`POST /ai/admin/wiki/build`（ADMIN）落库并**分开计**
        「新增 / 更新 / 未变动」；读者侧**公开**读主张，每条都带原文片段）
      E4-3 前端入口（读者侧展示）⏳ 下一刀
      E4 其余（实体消歧 / 关系 / 社区发现 / 页面生成 / 增量失效）⏳ 未开始
      GraphRAG 排在整个 LLM Wiki 之后。
```

> **做到哪了、还差什么，看 [`status.md`](status.md)**：逐阶段状态表 + 可执行核验命令 +
> 已知缺口。那份文件把「未开始」明确写出来，避免把计划读成进度。

## 2. 已确认的选型与口径（2026-09-24）

| 项 | 结论 | 说明 |
|---|---|---|
| 模型供应商 | **配置驱动**，前端面板填写并存库 | 默认给「国内云」一套：Chat 用 DeepSeek（`deepseek-chat` / `deepseek-reasoner`），Embedding 用 bge-m3（SiliconFlow / 智谱等 OpenAI 兼容端点） |
| 协议 | 只支持 **OpenAI 兼容** `/chat/completions` 与 `/embeddings` | 换厂商＝改 base_url + model 名，不改代码 |
| API Key 存储 | **存库**，AES-GCM 加密，主密钥 `AI_SECRET_MASTER_KEY` 只从环境变量读 | 面板**只写不读**：列表只返回掩码（`sk-…abcd`）与状态，永不回显明文 |
| 向量库 | **Qdrant**（服务器上已有），本机经 SSH 隧道或内网访问 | 不做内存向量库回退；本机无 Docker 时以服务器实例为准 |
| 嵌入维度 | 由 bge-m3 决定（1024）**但必须配置化** | 换模型要能重建集合（collection 带版本后缀 + alias 原子切换） |
| Java 侧职责 | 鉴权、配额、审计、协议转换、配置 CRUD、SSE 转发 | **不含任何 AI 算法**（见 §3 边界） |
| Python 侧职责 | 模型调用、Prompt、切块、嵌入、检索、融合、重排、引用、评测、Agent | 一切「换模型/换策略就要改」的代码都在这里 |

### 2.1 配置如何从面板流到 Python

```text
前端 /ai-lab 配置面板
   │ POST /ai/admin/providers（明文 Key，HTTPS + 登录 + ADMIN）
   ▼
ai-service：AES-GCM 加密 → 写 MySQL `ai_provider_config`
   │                                   ▲
   │ GET /ai/admin/providers（脱敏）    │ 读密文 + 用环境变量里的主密钥解密（内存中）
   ▼                                   │
前端看到掩码与连通性状态          stellar-ink-ai 侧读同一张表，解密后按逻辑角色取模型
```

- 主密钥 `AI_SECRET_MASTER_KEY`（32 字节 base64）**只从环境变量读**，不入库、不进日志；
  缺失时「写 Key / 改 Key」直接拒绝（读已有配置仍可用，避免把已配置好的环境锁死）。
- 加解密在 Java 与 Python **两侧各实现一份**，用**同一组测试向量**保证互操作
  （同一明文 + 同一 nonce → 两侧密文可互解）。
- 明文 Key 只出现在：请求体（HTTPS）、Java 内存、Python 内存。日志与异常一律脱敏。

## 3. 边界（与 `development-workflow.md` §7 一致，这里只强调新增部分）

- **配置读取**：Python 可以读 `ai_provider_config`（`ai_*` 表归 AI 域所有），但**不得读** `user`/`post`。
- **面板权限**：`/ai/admin/**` 一律 ADMIN；连「列出 provider」也是 ADMIN，因为返回内容本身是配置情报。
- **连通性自检**：面板上的「测试连接」由 Python 执行（它才是调用方），Java 只转发结果，
  返回内容不含 Key，只含：可达性 / 模型存在性 / 维度 / 延迟。
- **评测数据**：黄金集存 `ai_eval_*` 表 + `scripts/` 下的 JSONL 兜底，题目与标准答案由作者确认；
  评测运行**不写任何业务表**。
- **参数可调但不越权**：前端能调的是「检索策略与预算」（召回数、是否重排、切块大小…），
  不能调「权限范围」——可见文章范围永远由 Java 传入的 `userId`/`role` 与 `status=published` 决定。

## 4. 提交与验证节奏（沿用 `development-workflow.md`）

- 一刀一个可验证主题，≲ 300 行；每刀都要有可执行的验证命令与真实输出。
- 涉及前端的刀要跑 `npm run build`，并在 night/dusk/dawn 三主题下确认可用。
- 涉及 Qdrant / 模型调用的刀，先在**服务器实例**上验证一次，把命令与输出贴进汇报。
- 未拿到真实 Key 之前，Provider 层必须能用 **Fake Adapter** 跑通全部单测与评测流程。

## 5. 进度与起步顺序

1. **A1-1** ✅ `ai_*` 表与密钥加密：`10_ai-schema.sql`（provider 配置表 + 评测表骨架）、
   Java 侧 AES-GCM 工具与跨语言测试向量、Python 侧同款解密。
2. **A1-2** ✅ Provider 配置的 CRUD 与脱敏输出（Java），包含「测试连接」（当前为 TCP 自检）。
3. **A1-3** ✅ Python Provider 层：OpenAI 兼容 chat/embedding/rerank 三个接口 + Fake 实现 + 按角色路由。
4. **A1-4** ✅ 前端「AI 实验室 → 模型配置」面板：选 provider、填 Key、跑自检。
5. **A2-1** ✅ 网关 `/ai/**` 路由 + 角色门槛（`/ai/health` 公开；`/ai/admin/**` 任何方法都要 ADMIN）。
6. **A2-2** ✅ 内部 HMAC 签名（Java 签发 + Python 验签，跨语言向量守一致性）。
7. **A2-3** ✅ Python 接线：纯 ASGI 中间件在路由前验签，身份进 `request.state`；
   非生产暴露 `/internal/whoami` 自检接口。**跨语言 HTTP 冒烟已实测通过**。
8. **B2** ✅ 文章切块（父块 + 子块、标题路径、锚点、内容哈希、幂等），纯函数、无外部依赖。
9. **B3a** ✅ BM25（Sparse）+ RRF 融合 + Dense 余弦 + 混合检索开关（单路/多路可切）。
10. **B1** ✅（协议层）/ ⏳（真实联调）Qdrant 适配：`app/rag/qdrant_store.py` 用薄 HTTP 客户端实现
    建集合 / 写点（幂等 point id）/ 检索（含相似度下限）/ 按文章删点 / 健康检查，
    32 条协议测试用 `httpx.MockTransport` 把路径、请求体字段、响应取值与错误分类钉死。
    **连接方式已确认**：生产 Qdrant 只绑宿主机 `127.0.0.1:6333` 且无鉴权，本地走 SSH 隧道
    （见 `deploy/docker/README.md` 第十节），因此默认 `base_url` 就是 `http://127.0.0.1:6333`。
    顺手修掉一个会让排查跑偏的坑：内部服务必须 `trust_env=False` 绕开环境代理（见下表）。
    待办：隧道打通后跑一次 `uv run python scripts/qdrant_smoke.py`，把「按文档说对了话」
    升级为「对面确实这么答」。此外 `httpx` 已从 dev 依赖提为**运行时依赖** ——
    `app/providers/openai_compatible.py` 一直在模块顶层 import 它，按 main 依赖安装会 ImportError
    （本地装了 dev 所以一直没暴露）。
11. **B3b-2** ✅ 索引管道：`app/rag/index_pipeline.py` 把「切块 → 嵌入 → 建集合 → 清旧点 → 分批写入」
    编成写路径；`RetrievalPipeline.dense_store` 让 Dense 通路改为走向量库查询（不给 store 时仍走本地余弦，
    便于离线评测与对照）。离线端到端测试（`tests/test_qdrant_integration.py`）用一个内存 Qdrant 模拟器
    把写路径与读路径接起来跑通；真实 Qdrant 的一次冒烟仍待跑（见上）。
12. **C1–C3** ✅ 评测台主体：指标层 → 黄金集 v1（30 题，标注经证据自检）→ 策略对比运行器
    → 本地基线脚本（**不需要 Qdrant、不需要任何密钥**就能跑完整条评测链路，见下表）。
    **C3-1 评测接口已完成（Python 侧）**：`GET /eval/datasets`、`GET /eval/strategies`、
    `POST /eval/run`（受内部签名保护），命令行的默认五组与接口的默认五组是同一份；
    **C3-2 Java 侧已完成**：`AiContractPaths` 三个评测路径 + `PythonAiClient#evalDatasets/evalStrategies/evalRun`
    + 五个 DTO，请求与响应两份 fixture 两侧共读（响应样例由 `scripts/gen_eval_response_fixture.py`
    真实跑出来）；`ai-service` 暴露 `/ai/admin/eval/datasets|strategies|run`（全 ADMIN，
    网关 `/ai/admin/` 前缀已覆盖），并用 `InternalSignatureFeignInterceptor` 给所有发往 Python 的请求
    统一加 `X-AI-*` 签名头。
    **C3-2 前端已完成**：`/ai-lab` 增加「评测台」页签（URL `?tab=eval` 可分享）——
    选数据集 → 勾策略 → 跑一轮 → 对比表 + 逐题下钻（默认只看漏召/误拒/该拒未拒），
    `notes` 原文以暖色提示块展示。数据走 `stores/ai.js`，视图不直接请求后端。
    **C 阶段到此收口**；D1（问答编排，非流式）见下，D2/D3 待做。
13. **真实模型核验（2026-10-01）** ✅ 三角色实测 + 真实数字；⏳ 门限标定与吞吐问题
    - `scripts/provider_smoke.py`（**新增**）：按角色各打一次真实调用，把「配了」与「能用」分开。
      起因是一个**面板永远发现不了**的错误：`rerank` 的 `base_url` 被填成完整端点，
      而代码按约定再拼一次 `/rerank` → 真实请求打到 `/api/v1/rerank/rerank` → 404，
      报出来的话却是「模型名不存在」。修掉后 rerank 首名正确（0.7012 vs 0.0008）。
    - `scripts/compare_strategies.py` 增加 `--provider panel`：**同一份 CONFIGS、同一条编排**，
      只把模型来源换成面板配置 —— 不另写一套，避免「命令行数字」与「面板数字」分叉。
      实测：dense recall@1 0.8083（fake 0.075）、hybrid 0.8583 / MRR 1.0；`hybrid+rerank` 因免费档 429
      整行不可用（`errorCount=30`）。
    - `eval_runner` 的静默降级修掉：单题上游失败原先只记成「拒答」且**不打日志**，
      于是一次 429 让整行看起来像「开了重排就失效」。现在记 `error` + 打 `logger.warning`
      + 指标出 `errorCount` + `notes` 首条警示 + 命令行单独喊一行。
    - `app/providers/config_source.py`：读库异常（实测 1044 无权限）原先冒成 `code=500`，
      现在统一翻成 `ProviderConfigError`（说清读的是哪个库、该核对哪些变量，不回显密码）。
    - 待办：`minDenseScore` 标定（现有 `calibrate_min_score.py` 扫的是 BM25 的 `minScore`，
      Dense 门限没有入口）；嵌入缓存与 429 退避（否则标准五组跑不完）。
14. **E3-1 调用账** ✅（审计 + 成本）—— 回答「谁用了多少、花了多少、失败率多少」，
    此前只有零散的 `log.info`，日志会滚动、无法聚合、也不含成本。
    - **账记在 Java 侧**，三条理由都硬：身份（`userId`/`role`）只在 Java；`ai_*` 表归 ai-service；
      每次 AI 调用都必经这一层（也是 E3-2 配额拦截的同一层）。
    - `deploy/sql/12_ai_call_log.sql`：账表 + `ai_provider_config` 两个单价列（**幂等**，
      ALTER 走 `information_schema` 判断；已在干净库上验证过迁移路径）。
    - **单价按角色配、记账时快照**：事后改价不改写历史账目。没配单价 → 成本算不出来（NULL），
      由 `unpricedCalls` 如实暴露，**绝不当 0**（当 0 会让看板显示「本月花了 ¥0.00」，
      那是看起来最正常的一种假数据）。
    - **token 缺 = NULL ≠ 0**：Agent 与评测目前都不回报用量，记成「未计量」而不是「免费」。
      ⚠️ **流式的 token 记不到**：用量在 Python 的 `done` 帧里，而按既定设计 Java 不解析事件体
      （解析等于再抄一份 Python 事件契约）—— 要补齐得先给 `done` 事件定义 Java DTO。
    - 记账是 **best-effort**：入库失败只打 `warn`，绝不把成功的调用报成失败；但也不静默。
    - 埋点用接口的 default 方法 `AiUsageService.around(...)` 收口（计时/记账/异常分类只一处），
      连带好处是切片测试用 `@MockBean(answer = CALLS_REAL_METHODS)` 就能透传，不必逐个 stub。

### 等一个信息才能继续

**服务器上 Qdrant 的连接方式** —— 已从本轮改动里查清，不再是未知项：
`deploy/docker/docker-compose.yml` 把 Qdrant（`v1.12.4`）只发布到宿主机 `127.0.0.1:6333`、无鉴权，
编排网络内用 `qdrant:6333`。因此本地开发**走 SSH 隧道**（`deploy/docker/README.md` 第十节已有完整命令），
适配层默认 `http://127.0.0.1:6333` 正是这个形态。
剩下的唯一外部动作：**隧道打通后跑一次** `uv run python scripts/qdrant_smoke.py`（可带 base_url 参数），
它有两段：协议段（3 个手工向量证明协议说对了）+ 端到端段（真实切块 + Fake 嵌入，
跑一遍索引管道写库再用检索管道读回来），全程用临时集合、结束即删。

### B1 / B3b-2 阶段的落地记录（协议与接线都已锁，等一次真实冒烟）

| 能力 | 位置 | 说明 |
|---|---|---|
| 连接配置 | `QdrantConfig` | base_url / collection / api_key / timeout_ms / distance；`describe()` **不含 api_key** |
| 幂等 point id | `point_id_for` | chunk_id 是字符串，Qdrant 只收 uint64/UUID → SHA-256 前 8 字节并抹掉最高位 |
| 建集合 | `ensure_collection` | 维度不一致**报错而不是自动重建**（换嵌入模型必须显式 `recreate=True`） |
| 写入 | `upsert` | `?wait=true`；批次内维度不齐、与集合维度不符都当场失败 |
| 检索 | `search` | `score_threshold` 即 Dense 通路的拒答机制；命中缺 `chunkId/postId` 时报错而非送半成品 |
| 删除 | `delete_by_post_ids` / `delete_collection` | 按文章清理（改文/删文后重建），或整集合重建 |
| 错误分类 | `_decode` | 401/403 不可重试、429 与 5xx 可重试、其余 4xx 是参数错、响应非 JSON 明确报错 |
| 协议测试 | `tests/test_qdrant_store.py` | 32 条，用 `httpx.MockTransport` 断言路径/请求体/响应解析，**不需要容器** |
| 环境代理 | `trust_env=False` | 实测：本机装了代理时 httpx 会把 `127.0.0.1:6333` 交给代理并拿回空 502（看起来像 Qdrant 报错）；更要紧的是 `api-key` 会跟着进代理。内部服务一律绕开环境代理 |
| 索引写路径 | `app/rag/index_pipeline.py` | 嵌入失败发生在任何删除之前；`batch_size` 分批；本次重建的文章与已消失的文章都先删旧点 |
| Dense 后端可换 | `RetrievalPipeline.dense_store` | 给 store 就走向量库（拒答阈值一起传下去），不给就本地余弦 —— 两种可并排对照 |
| 索引一致性 | `RetrievalPipeline._position_of` | 向量库命中回不到本地语料时报错（索引与语料不是同一批），而不是错位引用 |
| 离线端到端 | `tests/test_qdrant_integration.py` | 5 条：写入 → 检索往返、改短文章清旧块、删文清点、阈值拒答、重跑幂等 |
| 真实冒烟 | `scripts/qdrant_smoke.py` | 隧道后跑一次；临时集合 `stellar_ink_smoke`，结束即删 |

### C 阶段的落地记录（纯 BM25 基线，语料 29 篇文章 / 41 个子块）

命令：`uv run python scripts/eval_local_baseline.py`（可带 `min_score` 参数）。

| 指标 | 值 | 口径 |
|---|---|---|
| Recall@1 / @3 / @10 | 0.833 / 0.942 / 0.942 | 只在有答案题上平均 |
| Precision@5 | 0.800 | post 级去重后计算 |
| NDCG@5 / MRR | 0.949 / 0.975 | MRR 0.975 = 20 题里 19 题首位就命中 |
| 拒答率 / 误拒率 | 0.4 / 0.0 | 拒答率的分母是 10 道无答案题 |
| 引用准确率 | 1.0 | 引用块必须来自检索块 |

**这份数字的用途是对照**：等 Qdrant 接上后，Dense / 混合 / 混合+重排 都要与它比，
否则无法回答「高级链路到底有没有用」。C 阶段的完整落地物与三条实测结论：

| 落地物 | 位置 | 说明 |
|---|---|---|
| 指标层 | `app/rag/metrics.py` | Recall/Precision/MRR/NDCG/Hit + 引用准确率 + 拒答率 + P50/P95；无答案题不进召回 |
| 黄金集 v1 | `tests/fixtures/eval/golden_v1.json` | 30 题 = 20 有答案 + 10 无答案；4 题带分级相关度 |
| 标注自检 | `scripts/check_golden_evidence.py`、`tests/test_golden_set.py` | 参考答案的短语必须能在**被标注的文章里**找到，两处共用一份判据 |
| 策略对比 | `app/rag/eval_runner.py` | `compare_strategies` 的输出就是前端对比表的形状；结果可映射 `ai_eval_run` |
| 检索管道 | `app/rag/pipeline.py` | 召回 → RRF → Rerank → post 级去重 → 空即拒答；`RetrievalConfig` 即前端实验室的开关 |
| Qdrant 适配 | `app/rag/qdrant_store.py` | 薄 HTTP 客户端（不引官方 SDK，避免悄悄退化成内存索引）；协议测试见 B1 小节 |
| 基线脚本 | `scripts/eval_local_baseline.py` | 用**同一条管道**只开 Sparse 一路，用来证明「评测链路真的能跑」并做对照 |
| 四路对比 | `scripts/compare_strategies.py` | 一次跑 sparse / dense / hybrid / hybrid+rerank / sparse+floor，输出对比表 |
| 门限标定 | `scripts/calibrate_min_score.py`、`score_distribution.py` | 扫绝对下限，打出「正确拒答 ↔ 误拒」的权衡曲线 |
| 评测接口 | `app/api/v1/eval.py`、`app/rag/eval_service.py`、`app/schemas/eval.py` | `POST /eval/run` 等三个内部接口；策略默认值与命令行同源；Fake 口径写进响应 `notes` |
| 契约样例 | `tests/fixtures/eval_run_request.json`、`eval_run_response.json` | Java 侧读同一份（请求 + 响应两个方向都锁住）；响应样例由脚本真实跑出来 |
| Java 契约 | `stellar-ink-ai-client` 的 `EvalRunRequestDTO` / `EvalRunResponseDTO` 等 5 个 DTO | 路径常量在 `AiContractPaths`；降级抛 503，不返回空对比表 |
| Java 出口 | `ai-service` 的 `AiEvalController`（`/ai/admin/eval/**`，全 ADMIN） | 只转发不加工；跑完记一条审计日志（谁、哪份数据集、几组策略、多少题） |
| 前端面板 | `views/ai/AiLabView.vue`（评测台页签）、`stores/ai.js` | 选数据集 → 勾策略 → 跑 → 对比表 + 逐题下钻；`notes` 暖色提示块原文展示；指标列由 `EVAL_METRIC_COLUMNS` 决定，缺列不显示 |
| 内部签名接线 | `InternalSignatureFeignInterceptor` + `InternalSecretProvider` + `SaTokenCallerProvider` | 身份取自 Sa-Token、traceId 取自 MDC；密钥缺失拒绝签名（不降级为不签名） |
| 乱码修复 | `PythonAiClient.java` | 该文件此前被写坏（UTF-8 当 GBK 读回再写），注释整段乱码且有 17 个私用区字符；已按下游约定重写 |
| 种子解析器 | `app/rag/seed_corpus.py`、`scripts/seed_posts.py`（CLI 壳） | 解析器搬到 app（`scripts/` 不进安装包）；失败抛库异常而不是 `SystemExit` |

**结论一（拦一个真 bug）**：种子解析器原先只读 `post` 的第一个 INSERT 块、只认带引号的时间戳，
于是 13–15 号短文被**静默丢掉**，评测语料少了三篇而没有任何报错 —— 召回率会因此长期偏低却没人怀疑。
现在按行扫三个块，并有用独立数法的回归测试（`tests/test_seed_posts.py`）盯着「少解析」。

**结论二（门限不能靠相对比例）**：`min_score_ratio` 按「最高分 × 比例」过滤，最高分自己永远过线，
所以**它永远不会让结果为空**，拒答率恒为 0；能让检索返回空从而拒答的只有绝对下限 `min_score`。
而两个分数分布是**重叠**的：有答案题里最低分 ≈ 14.3，无答案题里最高分 ≈ 24.1 ——
所以拒答不能靠继续拧 BM25 门限，要靠主题相关性判定或 Dense 相似度下限（B 阶段接上后重标）。

**结论三（四路对比：开关是真的，但 Fake 的 Dense 不能当质量）**：
`scripts/compare_strategies.py` 用同一条管道跑五组配置，结果如下（Recall@1 / Recall@3 / Precision@5 / 拒答率）：

| 配置 | 数字 | 说明 |
|---|---|---|
| `sparse` | 0.833 / 0.942 / 0.250 / 0.0 | 不加相对门限时前 5 名会混进弱候选 |
| `sparse+floor` | 0.833 / 0.942 / **0.800** / **0.4** | 与 `eval_local_baseline.py` 的数字完全一致（互为交叉验证） |
| `dense` | 0.075 / 0.167 / 0.070 / 0.0 | **接近随机**：FakeProvider 是哈希伪向量，无语义 |
| `hybrid` | 0.233 / 0.458 / 0.150 / 0.0 | 被 Fake 的 Dense 拖累 —— 说明融合真的按权重生效 |
| `hybrid+rerank` | 0.075 / 0.167 / 0.070 / 0.0 | 与 `dense` 完全相同：假重排用的就是那个伪向量函数，不带来新信息 |

读法：`dense` 接近随机**恰好证明向量通路真的在起作用**（没有偷偷退回 Sparse）；
`hybrid+rerank` 与 `dense` 相同则说明**重排必须换一个模型**（bge-reranker 之类）才有意义。

**这张表已经在真实模型上重跑过一次**（2026-10-01，`--provider panel`，真实嵌入 2048 维）：
`dense` 从 0.075 升到 **0.8083**、`hybrid` 到 **0.8583 / MRR 1.0** —— 与上面那条读法互相印证
（伪向量接近随机、真向量有语义）。**Qdrant 仍然没参与**：`assembly.pipeline_for()` 不传
`dense_store`，Dense 走本地余弦，所以真实数字不需要向量库也能拿。
`hybrid+rerank` 那一格因为免费档 429 整行作废（`errorCount=30`），仍待一次干净的运行。
数字与读数见 `status.md` §3。

### D1 阶段的落地记录（问答编排，非流式）

| 能力 | 位置 | 说明 |
|---|---|---|
| 编排 | `app/rag/qa.py` | 检索 → 摘录（编号）→ 提示词 → 模型 → 引用与结论；引用只列**送进模型的那些**段落 |
| 引用组装 | `Citation`（既有契约） | `postId` / `title` / `chunkIndex` / `snippet` / `score` 全部来自检索结果，不由模型输出决定 |
| 拒答 | `evidenceSufficient=false` + `doneReason=refused` | 没有候选时**不调用模型**；模型自己拒答时**保留引用**（两种信息要分开） |
| 预算控制 | `QaSettings` | `maxCitations` / `snippetLength` / `maxContextChars`：摘录总长度封顶，不把整库塞进上下文 |
| 接口 | `app/api/v1/qa.py` | 内网 `POST /qa`，受内部签名保护；装配走 `lru_cache`（避免「每问一句嵌入整库一遍」） |
| 测试 | `tests/test_qa.py`、`tests/test_qa_api.py` | 18 条：引用与摘录一一对应、无依据不调模型、预算封顶、契约形状、鉴权、不泄露内部信息 |

### D2 阶段的落地记录（问答入口，深读页）

| 能力 | 位置 | 说明 |
|---|---|---|
| 对外接口 | `ai-service` 的 `AiQaController`（`POST /ai/qa`） | 门槛是**登录**（读者功能）；只做协议转换，答案与引用全部来自 Python |
| 浏览器请求体 | `shared-model` 的 `AiAskDTO` | 与内部 `QaStreamRequestDTO` **刻意分开**：内部随 Python 契约走，这层对前端负责 |
| 前端入口 | `views/read/ReadView.vue` 的「问星笺」面板、`stores/qa.js` | 未登录给登录入口；答案按 `whiteSpace:pre-wrap` 保留换行；**拒答有独立样式**；`usage.model=fake` 时挂「离线自测」提示；引用可点回原文（同篇不跳） |
| 为什么先非流式 | — | 「检索 → 引用 → 拒答」已经能用，先把它交付出来；SSE 要等协议转换与「断开即终止下游」一起做，否则是一条没人消费的通道 |

### D3 阶段的落地记录（Copilot 编排 + 执笔页差异预览）

| 能力 | 位置 | 说明 |
|---|---|---|
| 编排 | `app/rag/writing.py` | 任务指令 + 风格目标 + 草稿 → 提示词；输出解析优先 JSON、退路是 `---` 分隔 |
| 草稿保护 | `_truncate` + 日志口径 | 超长草稿按「首 + 尾」截断（开头定调子、结尾是续写接点）；日志只记任务与用量，**不记草稿** |
| 解析容错 | `parse_candidates` | 「模型没按格式回答」**必须报错**：静默返回空候选会让作者以为「没什么可改」；标题/标签/摘要这类单值任务才接受纯文本 |
| 离线可测 | `FakeCopilotChat` | `FakeProvider` 只会回显提示词，会让所有润色请求变成 502；因此单独做了一个**按格式回答**的桩（候选是草稿句子切片，`rationale` 自报「离线自测」） |
| 对外接口 | `AiWritingController`（`/ai/writing/suggest`） | AUTHOR 门槛；**没有任何写入路径**，只把候选返回到前端 |
| 差异预览 | `utils/diff.js` + `components/ai/DiffView.vue` | 手写 LCS（不引 diff 依赖）：先删后加的差异块、上下文折叠、超长退化为整段替换 |
| 采纳动作 | `utils/copilot-action.js` | **纯函数**决定候选落在正文的哪一部分：润色＝替换、续写＝插到光标、提纲＝追加、标题＝只改标题、标签/摘要＝只复制；未知任务**退到「只复制」** |
| 前端面板 | `components/ai/CopilotPanel.vue`、`stores/copilot.js` | 执笔页侧栏（仅作者）；6 个功能按钮 + 风格/条数 + 补充要求；离线桩标「未接真模型」；**没有「自动应用」开关** |
| 测试 | `tests/test_writing.py`、`tests/test_writing_api.py`、`AiWritingControllerTest`、`npm run check` | 后端 32 条 + 前端 28 条自检：解析两条路 + 失败要报错、草稿不外泄、字面量映射、AUTHOR 门槛、离线桩必须出候选、**采纳动作映射（错一个就会抹掉正文）** |

**D3 的两条取舍，写在这里免得后人改动时丢掉**：
① **没有「自动应用」开关**。面板只能把候选**渲染**出来，正文的每一次改动都要作者点一下；
   一旦有了开关，AI 就能在作者没看的情况下改掉正文 —— 那正是这条红线要防的事。
② **草稿快照跟着候选走**。差异预览比的是「发请求那一刻的草稿」，不是「现在的草稿」：
   作者在结果返回后继续打字时，预览不会跟着漂移；面板也会在每次请求后清空旧候选，
   免得把上一轮的候选误当成这一轮的建议。

### D2s 阶段的落地记录（SSE 流式，Python 侧）

| 能力 | 位置 | 说明 |
|---|---|---|
| 事件契约 | `app/schemas/qa_stream.py` | 帧格式 `data: {json}\n\n`，**类型写在 JSON 里**（`meta`/`citation`/`delta`/`done`/`error`）而不是 `event:` 名：Java 不必维护事件名表，前端一个解析器通吃 |
| 事件顺序 | 同上（模块 docstring 就是契约） | `meta` → `citation*` → `delta*` → `done`。**引用先于增量**：引用由检索决定，不必等模型；`done` 缺失会让前端永远停在「生成中」 |
| 流式编排 | `app/rag/qa.py` 的 `stream()` | 与非流式 `answer()` **共用同一套检索/摘录/拒答判定**；有 `stream_chat` 就边生成边吐，没有就退化成**一个** `delta`（内容一样完整，只是少几次增量） |
| 取消传播 | `app/api/v1/qa.py` 的 `_sse_frames` | 生产者任务 + 队列：**浏览器断开 → ASGI 关闭生成器 → `finally: task.cancel()` → 上游 HTTP 流关闭**。队列的另一个用处是静默期插 `: ping` 心跳 |
| 关掉代理缓冲 | 响应头 | `Cache-Control: no-cache` + `X-Accel-Buffering: no`：漏了后者，Nginx 会把「流式」攒成一整块再吐出来 |
| Provider 流式 | `app/providers/openai_compatible.py` 的 `stream_chat()` | SSE 逐行解析（残行留到下一块，TCP 分片会把一行 JSON 劈开）；`stream_options` 被 400 拒绝时**去掉它重试一次**；异常路径也关连接（`async with`） |
| 测试 | `tests/test_qa_stream.py`、`tests/test_qa_stream_api.py` | 21 条：事件顺序、增量不被合并、无依据不调模型、模型自拒答保留引用、回落路径、心跳、帧形状、代理头、空问题 422 |

**这一刀踩到的坑（值得单独记）**：`InternalAuthMiddleware` 读完 body 后原本一律返回
`http.disconnect`，**非流式接口完全正常，流式接口直接 500**（Starlette 报 "No response returned"）。
原因是 `BaseHTTPMiddleware`（traceId 中间件）在响应进入流式发送后会调用 `receive()` 等断开信号，
拿到伪造的「已断开」就把整个响应任务组取消，而 `http.response.start` 还没发出去 ——
**伪造断开等于自己掐断自己的流**。现在第二次起交回真实 receive，并有回归测试盯着这个不变量。

### D2s 阶段的落地记录（SSE 流式，Java 出口）

| 能力 | 位置 | 说明 |
|---|---|---|
| 帧模型 | `ai-service/stream/QaSseFrame.java` | 记录「事件类型 + 原始帧」。转发用 `raw()`，**Java 不重新编码事件体**；类型解析不出时标 `unknown` 而不是猜 |
| 流式通道 | `ai-service/stream/HttpQaStreamClient.java` | JDK `HttpClient` 直连 Python（**不引依赖**）：**Feign 的解码器是「拿完整 body」语义，会把 SSE 退化成一次性响应**，所以这条流单独开通道。签名头复用 `InternalRequestSigner`，与 Feign 那条完全同口径 |
| 帧切分 | 同上的 `FrameIterator` | 按**空行**聚合成帧再转发（拆开写会让浏览器看到半截 JSON）；心跳 `: ping` 这类**注释行不是帧**，必须丢弃 |
| 出口 | `ai-service/controller/AiQaStreamController.java` | `ResponseBodyEmitter`（不用 `SseEmitter`：后者按 `event:` 名分帧，而本协议把类型放在 JSON 里）；上游失败发一帧 `error` 而不是返回空流 |
| 取消传播 | 同上 `forwardFrames` 的 `try-with-resources` | 浏览器断开 → `send` 抛 `IOException` → 关闭下游句柄 → 上游断开 → Python 生成器关闭 → 模型停止。**正常结束/上游异常/客户端断开三条路都要关**，所以用 try-with-resources 而不是三处手写 close |
| 405 修正 | `ErrorCode.METHOD_NOT_ALLOWED` + `GlobalExceptionHandler` | 路径存在但方法不对原本落进兜底返回 500「系统繁忙」——用户看起来像服务坏了。新增路由时特别容易撞上（上一秒还 404，下一秒变 500） |
| 测试 | `HttpQaStreamClientTest`、`AiQaStreamControllerTest` | 12 条（各 6）：起**真实** `HttpServer` 验证逐帧到达与「close 真的断开上游」（mock 掉就只是在测自己的假设）、心跳不成帧、坏帧不中断、非 2xx 不返回空流、写失败必须关下游、未登录 401 |

**一条断言随路由落地而改变语义（记下来当教训）**：`AiHealthControllerTest` 原先用
`GET /ai/qa/stream` 当「未知路径」的代表。D2s 真的把它实现出来之后，同一句断言的语义
从「路径不存在」变成「方法不对」，而它当时报的是 **500** —— 测试红了，但红的原因和它想守的东西无关。
现在拆成两条：不存在的路径给 404（用 `/ai/not-implemented-yet`），存在但方法不对给 405。

### D2s 阶段的落地记录（SSE 流式，前端消费方）

| 能力 | 位置 | 说明 |
|---|---|---|
| 切帧 | `stellar-ink-web/src/utils/sse.js` | `parseFrame`（`data:` 行 + JSON 里的 `type`）、`FrameSplitter`（增量切帧，**半帧留到下一块**）、`readFrames`（异步迭代 + `reader.cancel()`）。抽成纯类是为了能被 `npm run check` 直接断言 |
| 状态拼装 | `stores/qa.js` 的 `askStream`/`applyFrame` | `meta`→模型标识、`citation`→追加引用、`delta`→追加正文、`done`→收尾；**`done` 未到就提示「回答中断」**，不装作答完了。中止（`abort`/`reset`/离开页面）会关掉这条流 |
| 界面 | `views/read/ReadView.vue` 的「问星笺」 | 流式渲染正文 + 光标、「正在检索文章…」占位、中断用暖色提示（不是错误）、「停止」按钮；引用先到先渲染，不用等答案 |
| 部署缺口 | `vite.config.js`、`deploy/docker/nginx/default.conf`、`scripts/deploy-selfcheck.mjs` | `/ai` **两处代理都漏了**（问星笺/Copilot/评测台在 dev 与生产都会静默失败）；现补上，并给 nginx 的 `/ai` 单独配 `proxy_buffering off` + 120s 读超时 + 独立限流档（30r/m） |

**这一刀最有价值的发现不是代码，而是部署配置**：`/ai` 从来没进过 Vite 代理与 nginx location，
而 D2/D3 的功能都写着「已完成」—— 没有一个人真的在浏览器里点过它们。
新增的 `scripts/deploy-selfcheck.mjs` 把「前端用到的接口前缀必须在两处代理里都出现」变成 `npm run check` 的一部分：
漏一次就红，而不是等到线上「请求成功但页面空白」。

**顺带一个通用的教训**：`EventSource` 只支持 GET。凡是「必须 POST 又要流式读」的场景，
都只能 `fetch` + `ReadableStream`，而这条路上**没有现成的重连/事件名机制**，
所以协议把事件类型放进了 JSON（见上）—— 两侧都少一层需要同步的表。

### E1 阶段的落地记录（写作记忆与风格画像）

| 能力 | 位置 | 说明 |
|---|---|---|
| 画像算法 | `app/rag/style.py` | 字符级扫描，**不引分词库**：句读切分 + 拉丁词边界就够量句长/标点/关联词；长度口径「中日韩按字 + 拉丁按词」（`Redis` 算一个词），句长取中位数（比均值稳） |
| 不引用原句 | `_common_phrases` + `StyleSettings.phrase_min_count=3` | 字组只在**反复出现（≥3 次）**时给出，长度 3–6 字。出现一次的是内容，出现三次以上才是习惯；阈值降到 1 会被参数校验直接拒绝（那等于允许摘录原句） |
| 数据边界 | `app/api/v1/style.py` 的 `_samples_for` | 只吃已发表文章（当前＝种子内容包）；接真实数据源时必须显式写成 `status = published` —— 草稿进画像等于把未发表内容推进提示词 |
| 样本不足是数据 | 同上 + `WritingStyleResult` | 返回 `evidenceSufficient=false` + `profile=null` + 带**实际篇数与字数门槛**的 `notes`。**0 与「没量」是两件事**：给一堆 0，作者会以为自己的文章「没有风格」 |
| 契约 | `app/schemas/style.py`、`tests/fixtures/writing_style_{request,result}.json` | 结果 fixture 由 `scripts/gen_style_fixture.py` 用**固定样本**生成（不读种子语料）：种子一变契约测试就红的那种红没有信息量 |
| Java 出口 | `WritingStyle{Request,Result}DTO` + `/ai/writing/style` | 门槛 AUTHOR；`authorId` **取登录身份**，对外 DTO 里根本没有该字段（客户端传了也无效）；`WritingStyleProfileDTO.hasContent()` 标 `@JsonIgnore`，避免便利方法漏进 JSON |
| 前端 | 执笔页 Copilot 面板的「我的写作画像」折叠块 | 默认收起、只读；显示句长中位/短句占比/逗号密度/样本数与关联词、字组、常写主题；样本不足时显示服务端那句人话 |
| 测试 | `tests/test_style.py`、`test_style_api.py`、`test_style_contract.py`、`AiWritingStyleControllerTest` | 算法 + 接口 + 契约 + 出口共 22 条：不引用原句、代码块不计入、样本不足给人话、`authorId` 取登录身份、便捷方法不漏 JSON |

**两处踩到的坑，都记在这里免得后人重踩**：
① **测试断言的取样范围必须与实现一致**。画像只取前 20 篇，我先按全部 25 篇数了一遍关联词，
   于是排名差一位、测试以「差一位」的形态红给我看 —— 看起来像算法不稳定，其实是测试自己数错了范围。
② **不要拿真实语料的边界值测边界**。我本想用「种子里只写了 2 篇的作者」验证「样本不足」，
   结果那两篇合计 569 字，恰好过了 500 字下限。改成用「同一作者、只取前 3 篇」来构造 ——
   否则测的是语料，不是代码。

### E2 阶段的落地记录（只读 Agent 核心）

| 能力 | 位置 | 说明 |
|---|---|---|
| 决策协议 | `app/rag/agent.py` | 模型每步输出一个 JSON：`{thought, tool, arguments}` 或 `{thought, final, citations}`。**类型写在 JSON 里**便于解析与审计；`thought` 只进审计不进答案 |
| 工具只读 | `ToolBox.__init__` | `read_only=False` 的工具在**装配时**就被拒绝。与其运行期判断「这个工具能不能调」，不如让它根本进不来 —— 后者不可能被漏判（红线 §7.4） |
| 三维预算 | `AgentSettings` | 步数 / 工具调用次数 / 观察字符数**三者都要**：只限步数挡不住「一步塞十个调用」，只限次数挡不住「一次观察灌回整篇文章」。另有单条工具结果的独立上限，否则第一次观察就把总预算吃光 |
| 引用核实 | `_verified_citations` | 模型只能标 `postId`；片段与分数由工具结果贴回。声称但没观察到的引用**一律丢弃**；一条都没标对时退化为「把观察到的带上」。第一版还想用 `Citation` 装「模型给的线索」，pydantic 立刻以「snippet 不能为空」拒绝 —— 类型层面就证明了引用不能由模型给 |
| 中断 | `should_stop` + `_Interrupted` | 只在**步与步之间**检查：工具执行中途不打断（将来加写操作时这条边界很关键）。中断记为 `interruptedBy=caller`，与「预算用尽」区分开 |
| 离线诚实 | `app/api/v1/agent.py` | Fake 模型解析不出决策 JSON，于是走「格式不符」分支并在预算内收尾，返回 `doneReason=length` + `steps[].error`。**不做的事**：编一个看起来像答案的回显。假装配下最危险的是「随便返回点什么，看起来成功了」 |
| 出口 | `AiAgentController` | 门槛登录（不比问答多权限）；服务端默认预算 4 步 / 6 次，比契约上限（8 / 12）更紧，且 `bounded()` 取 min —— **客户端只能收紧**；`doneReason=length` 与空答案原样透传 |
| 测试 | `tests/test_agent.py`、`test_agent_api.py`、`AiAgentControllerTest`、`AiContractTest` | 28 条：预算三种触顶、中断、坏输出不毁整轮、未知工具名反馈给模型、引用必须被观察到、写工具装不进来、离线不假装答完、预算只能收紧 |

**这一刀反复出现的同一个 bug 值得记住**：我把「工具调用次数」在**拿到可用观察之前**就加了 1，
于是「第三次调用只得到空观察」也被计进账 —— 预算账目与实际花掉的钱对不上。
这类偏差不会报错、也不会让测试红（除非专门去数），只会让账单和日志各说各的。
修法是把「先算观察预算再计调用」写进注释：**先确认拿到了东西，再记账**。

**一条实测教训（写在这里免得后人踩）**：离线自测用的是 Fake 哈希伪向量，余弦在 **0.03 量级**，
最初给 `minDenseScore` 设了 0.2 —— 结果向量通路被**静默清空**，混合检索退化成纯 BM25，
而日志、指标、响应全都正常。已改为 0 并加了一条测试盯着它：
**给离线链路设「看起来合理」的阈值，比不设更危险。**



| 能力 | 位置 | 说明 |
|---|---|---|
| 密钥加密 | `common-core/crypto/AesGcmCipher`、`app/core/crypto.py` | AES-256-GCM，密文 `v1:<nonce>:<ct+tag>`；主密钥仅环境变量 |
| 密钥一致性 | `tests/fixtures/key_vector.json` | Java 生成、两侧单测共读；改格式两侧同时红 |
| 配置 CRUD | `ai-service` 的 `AiProviderAdminController` | 五个接口全 ADMIN；**没有回读明文 Key 的接口** |
| 连通性自检 | `ProviderConnectivityChecker` | 只做 TCP 可达（`scope: tcp_only`），不冒充「模型可用」 |
| 供应商层 | `app/providers/` | 三类接口 + OpenAI 兼容实现 + 确定性 Fake + 按角色路由 |
| 前端面板 | `views/ai/AiLabView.vue`、`stores/ai.js` | 头像菜单入口（仅 ADMIN），预填常见厂商端点与模型名 |
| 内部签名 | `aiclient/signature/`、`app/core/internal_auth.py` | 标准串含**身份字段**；±60s 时间窗 + nonce 防重放；角色白名单 |
| 签名一致性 | `tests/fixtures/signature_vector.json` | 由 `scripts/gen_signature_vector.py` 生成，两侧单测共读 |
| 验签接线 | `app/core/internal_auth_middleware.py` | **纯 ASGI**（路由前生效）：公开路径全等白名单，其余默认拒绝 |
| 跨语言冒烟 | `InternalAuthSmoke` | Java 签名 → Python 验签的真实 HTTP 往返（含篡改身份被拒） |

**A 阶段修掉的缺陷**（都在鉴权/协议边界上，值得记住）：
① `ai-service` 漏配 `SaTokenConfigure`（角色判断变 500）；
② `AiModelRole` 未声明 JSON 字面量（前端传 `"chat"` 变 400）；
③ `AuthHelper` 未兜住 Sa-Token 异常（无 token 时 500 而不是 401）；
④ 标准串最初**没签身份字段** —— 内网中间人改 `X-AI-User-Id` 即可冒充 ADMIN（会话内自查发现）；
⑤ Python 验签用大小写敏感的 `dict(headers).get("X-AI-Signature")` —— httpx 发小写头名，
   表现为「带了签名却说没带」（跨语言必踩）。

