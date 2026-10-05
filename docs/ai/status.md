# 星笺 AI 阶段状态（截至 A→E 第 20 轮）

> 这份文件是**核验报告**，不是宣传：每一行都对应仓库里可运行的命令或可读的文件。
> 有疑问的地方一律写成「未做 / 待验证」，不写成「已完成」。
> 排序与口径以 `docs/ai/fast-track-plan.md` 为准，工程约定以 `AGENTS.md` 为准。

## 1. 一句话结论

**A / B / C / D 四阶段与 E1、E2 已完成并可执行验证；E3 只完成了第一刀（调用账），E4 未开始。**
另有三处**已知缺口**写在 §4，它们不影响「功能可用」，但影响「可以放心用真实模型与真实流量」。

**真实模型链路（2026-10-01 实测）**：`chat` / `embedding` / `rerank` 三个角色都已配好，
并**各打通过一次真实调用** —— `uv run python scripts/provider_smoke.py` 3/3 通过
（chat 702ms / embedding 2048 维 / rerank 首名正确）。真实嵌入下的检索数字也跑出来了，
见 §3 的第二张表。**仍未实测的是 Qdrant 的真实往返**（§4.2）；`minDenseScore` 的标定被
免费档的**每日**额度挡住（§4.1，已定位为「用户侧动作」而不是代码缺陷）。

## 2. 逐阶段核验

| 阶段 | 状态 | 关键产物 | 核验方式 |
|---|---|---|---|
| A1 模型配置中心 | ✅ | `ai_*` 表 + AES-GCM 密钥加密 + Provider CRUD 脱敏 + Provider 层 + `/ai-lab` 面板 | `mvn -pl ai-service -am test`；`tests/test_internal_auth_wiring.py` |
| A1+ 模型库与角色绑定 | ✅ | `ai_model` 素材库（`deploy/sql/11_ai_model_library.sql`）+ `/ai/admin/models` + 角色下拉绑定（`PUT /ai/admin/providers/{role}/model`）；Python 仍只读角色表 | `AiModelLibraryServiceImplTest`；`npm run check`（`scripts/ai-store-selfcheck.mjs` 盯写反馈） |
| A2 安全调用链 | ✅ | 网关 `/ai/**` 路由与角色门槛；内部 HMAC（**标准串含身份字段**）+ 纯 ASGI 验签 | `mvn -pl gateway-nacos-sentinel -am test`；签名向量 `tests/fixtures/signature_vector.json` |
| B1 Qdrant 适配 | ⚠️ 代码完成、**真实冒烟未跑** | `app/rag/qdrant_store.py`（幂等 point id、错误分类、`trust_env=False`） | `tests/test_qdrant_store.py`（32 条 MockTransport 协议）；见 §4.2 |
| B2 切块索引 | ✅ | `app/rag/chunking.py`（父子块、标题路径、锚点、内容哈希） | `tests/test_chunking.py` |
| B3 混合检索 | ✅ | `app/rag/pipeline.py` + `retrieval.py`（BM25 / 余弦 / RRF / 可开关重排） | `uv run python scripts/eval_local_baseline.py`；`scripts/compare_strategies.py` |
| C 评测台 | ✅ | 指标层 + 黄金集 v1（30 题）+ 策略对比 + `/ai/admin/eval/**` + `/ai-lab?tab=eval` | 同上两条脚本；`tests/test_eval_api.py`；`AiEvalControllerTest` |
| D1/D2 问答 | ✅ | `app/rag/qa.py` + `/ai/qa` + 深读页「问星笺」 | `tests/test_qa*.py`；`AiQaControllerTest` |
| D2s 流式问答 | ✅ | `/qa/stream` 事件契约 + `ResponseBodyEmitter` 出口 + `utils/sse.js` 流式渲染与中止 | `tests/test_qa_stream*.py`；`HttpQaStreamClientTest`；`npm run check` |
| D3 Copilot | ✅ | `app/rag/writing.py` + `/ai/writing/suggest` + 执笔页差异预览与人工采纳 | `tests/test_writing*.py`；`AiWritingControllerTest`；`npm run check` |
| E1 写作记忆 | ✅ | `app/rag/style.py`（**不引用原句**）+ `/ai/writing/style` + 只读画像面板 | `tests/test_style*.py`；`AiWritingStyleControllerTest` |
| E2 只读 Agent | ✅ 含前端入口 | `app/rag/agent.py`（三维预算 + 引用核实 + 中断）+ 只读工具 + `/ai/agent/ask`；前端 `stores/agent.js` + 阅读页「深挖」模式（步骤可见、三种「没答案」形态分开显示） | `tests/test_agent*.py`；`AiAgentControllerTest`；`scripts/agent-selfcheck.mjs`（22 条，并入 `npm run check`） |
| E3-1 调用账（审计 + 成本） | ✅ | `deploy/sql/12_ai_call_log.sql`（账表 + 角色单价两列）+ 五条路径埋点（qa / qa_stream / writing_suggest / agent / eval）+ `GET /ai/admin/usage/summary`（ADMIN） | `AiUsageServiceImplTest`（H2 真落库 + 成本快照 + 缺口计数）；`AiUsageControllerTest`（门槛与形状） |
| E3-2 配额与并发 | ✅ | 额度在配置（`stellar.ink.ai.quota.*`）、计数在 Redis（`stellar-ink:ai:quota:`，自然日窗口）；拦截挂在 `AiUsageService.around`（调用前检查、调用后计数）；触顶 429、Redis 故障 fail-open + warn；ai-service 放开 `RedisUtils` | `AiQuotaPolicyTest`（窗口/上限边界/键名）；`AiUsageQuotaTest`（10 条：用户·角色·并发三维触顶、回滚、fail-open、拒绝时不调用下游） |
| E3-3 MCP 工具服务 | ✅ | `app/mcp/protocol.py`（JSON-RPC 2.0 信封与错误码）+ `app/mcp/server.py`（`initialize`/`ping`/`tools/list`/`tools/call`）+ `POST /mcp`（内部签名保护）；`ToolSpec` 增加 `input_schema`/`required_role`/`timeout_ms`/`idempotent`；工具集与 Agent **同一份** | `tests/test_mcp_server.py`（21 条：信封、越权 `-32003`、schema 外参数 `-32602`、工具失败 `isError`、超时、通知不回响应）；`tests/test_mcp_api.py`（8 条：签名、身份透传、错误体形状） |
| E3-4 观测出口 | ✅（最小形态；OTel/Langfuse **待拍板**） | Python：进程内事件缓冲（有界、**只存结构不存内容**）+ 三段埋点（检索 / 工具 / 模型含失败状态码）+ `GET /internal/trace/{traceId}`；Java：`GET /ai/admin/trace/{traceId}` 合并调用账与 Python 事件，**Python 不可用时仍回账** | `tests/test_trace.py`（9）+ `test_trace_api.py`（3）+ `test_trace_contract.py`（3，与 Java 共读 fixture）；`AiTraceControllerTest`（6）+ `AiUsageServiceImplTest.traceCalls` + `AiContractTest.traceReplayRoundTrips` |
| E4-1 带证据的主张抽取 | ✅ | `app/rag/wiki.py`（原子主张 + **引用校验** + 丢弃分类计数）+ `POST /wiki/claims`；主张绑定 `postId`/`chunkIndex`/`postVersion`/`contentHash`/`quote` | `tests/test_wiki_claims.py`（22 条：编造引用被挡、跨段落引用不算、空白差异不算不实、重复计数、长度边界、格式抖动不炸、契约样例可解析）+ `test_wiki_api.py`（4 条） |
| E4-2 落库与读者侧 | ✅ | `deploy/sql/13_ai_wiki.sql`（`ai_wiki_claim`，**幂等锚点 (postId, contentHash, claimText)**）+ `POST /ai/admin/wiki/build`（ADMIN；落库三种结果分开计数）+ 读者侧**公开**读（`GET /ai/wiki/posts/{id}/claims`、`/ai/wiki/claims/count`）；构建走调用账 `scene=wiki` | `AiWikiServiceImplTest`（5 条：首次全新增、**重复构建未变动**、置信度变了是更新、缺证据不落库、读者侧按段落排序）+ `AiWikiControllerTest`（6 条：两档门槛不同、预算只能收紧、证据一起回）+ `AiContractTest.wikiClaimsRoundTrips` |
| E4-3 前端入口（读者侧展示） | ✅ | 阅读页「知识条目」区块（`stores/wiki.js` + `utils/wiki.js`）：主张与**原文片段并排**，可「在正文中定位」；**没有条目时整块不出现**，取数失败**静默降级**（`failed` 与 `claims` 是两件事） | `scripts/wiki-selfcheck.mjs`（20 条：成功/无条目/失败三态分开、切文先清空、空 id 不发请求；定位的 located/missing/unavailable、空白差异、过短片段不跳） |
| E4-4 实体抽取与别名合并 | ✅ | `app/rag/entities.py`：实体必须能在**留下来的主张**或它的原文片段里逐字找到（否则丢弃计数）；合并只做确定性归一化（全角/半角/大小写/空白/首尾标点）；**不加模型调用**（与主张同一次请求） | `tests/test_wiki_entities.py`（11 条：实体必须有证据、被丢弃主张里的实体跟着消失、归一化合并、长度边界、顺序确定）+ 契约样例（含 `entityNotInText`）+ `AiContractTest` |
| E4-5 实体共现关系 | ✅ 前半（Python） | `relation_edges`：同一句主张里同时出现的实体连边，`weight` = 被一起谈论的主张条数，**每条边都带证据**（哪几句主张）；无向边只有一种表示。⚠️ 如实叫「共现」而不是语义关系（因果/属于/依赖要模型抽取 + 人工审核） | `tests/test_wiki_entities.py`（+5 条：同句才连边、边带证据、重复共现加权、顺序确定）+ 契约样例 + `AiContractTest` |
| E4-6 实体/关系落库 | ✅ | `deploy/sql/14_ai_wiki_entity.sql` 四张表（实体按 `normalized` 幂等 / 提及含 `claimText` / **无向**关系 / 关系证据）+ `AiWikiServiceImpl.storeGraph`；端点在库中不存在时**这条边不落库**；关系证据**先清后写** | `AiWikiServiceImplTest`（+3：写法差异只占一行、重复构建权重更新且证据不累加、端点缺失跳过）+ 构建报告口径分清（落库数 vs 模型侧账） |
| E4-7 读者侧实体 | ✅ | `GET /ai/wiki/posts/{id}/entities`（**公开**）：实体 + **本文**的提及 + 共现关系（另一端带名字、边带证据）+ 全站计数；前端「知识条目」区块下多一段「本文提到的实体」（如实标「共现，不是因果」） | `AiWikiServiceImplTest`（+2：只带本文提及与关系、无提及返回空）+ `AiWikiControllerTest.entitiesArePublic` + `wiki-selfcheck.mjs`（+10 条：两条路径独立失败、切换文章清空、空 id 不发请求） |
| E4-8 主题社区发现 | ✅ | `app/rag/topics.py`：**连通分量 + 边权阈值**（刻意不用 Louvain：确定性 + 可解释 + 失败方式看得见）。孤立实体**单独报出**、不硬塞归属；阈值是 v1 唯一的收窄手段；主题名是**关键词组合**（不是编出来的标题） | `tests/test_wiki_topics.py`（9 条：两组分开、孤立实体报数、传递链成一组、阈值真的收窄、顺序确定、名字来自本主题、截断不静默、端到端闭环）+ 契约样例 + 两侧契约测试 |
| E4-9 主题落库与读取 | ✅ | `deploy/sql/15_ai_wiki_topic.sql` 三张表（主题 / 成员 / 证据）+ `storeTopics`：**幂等锚点是成员签名**（成员规范化名字排序后的 SHA-256）而不是主题名 —— 名字由成员算出来，拿它当锚点会凭空多出一行；成员与证据**先清后写**；`GET /ai/wiki/posts/{id}/topics`（**公开**） | `AiWikiServiceImplTest`（+3：签名锚点与读者侧、重复构建不累加、无证据返回空）+ `AiWikiControllerTest.topicsArePublic` |
| E4-10 主题页前端 | ✅ | 阅读页「本文参与的主题」：一行看「叫什么、多大、涉及几篇」，按需**展开成员与原文**（不新增一级导航 —— 它固定 6 项）；主题与条目/实体是**三套独立状态** | `wiki-selfcheck.mjs`（+9 条：主题独立失败、失败不影响条目与实体、空列表 ≠ 失败、换文章清空、空 id 不发请求） |
| E4-11 增量失效 | ✅ **E4 收口** | `app/rag/staleness.py`：三种状态**分开报**（`current` 不用动 / `stale` 内容变了→重建 / `orphan` 段落没了→清理）；判定**只看段落哈希**、哈希缺失按 current（否则会逼人做全量重建）。定向重建：`POST /wiki/claims` 的 `postIds`（「就要这几篇」，与 `maxPosts` 的「按顺序取几篇」是**两个意图**）；`GET /ai/admin/wiki/stale`（ADMIN）**只报告不重建** | Python `tests/test_wiki_staleness.py`（8 条）+ `tests/test_wiki_api.py`（+5 条：定向只抽点名文章、超长列表 422、盘点三态分开、空列表说「不用重建」）+ Java `AiWikiServiceImplTest`（+2）+ `AiWikiControllerTest`（+3：读者 403、报告可执行、定向去重保序、封顶） |
| E5-1 GraphRAG 图检索核心 | ✅ 前半（Python） | `app/rag/graph.py`：**Local Search**（问题里命中的实体 → 沿共现边一跳 → 收集这一片的主张与原文）+ **Global Search**（按主题聚合，回答「覆盖了什么」）。每条结果带 `via`（凭什么捞出来）；**没命中就说没落点并回退向量检索**，不拿弱相关的边充数 | `tests/test_rag_graph.py`（11 条：种子命中、一跳不跨社区、最长实体优先、没命中不是错误、阈值过滤、截断不静默、结果可复现、全局按关键词命中、主题未知如实说） |
| E5-2 GraphRAG 接成评测策略 | ✅ 接线（**数字待真实额度**） | `GraphRetriever`（`Retriever` 协议）+ `graph_from_payload`（**直接从 `/wiki/claims` 返回体装图**，不造第二份格式）+ 评测请求的 `enableGraph` / `graph`：没带图时如实回一条「本次没带图」的行，而不是少一列或显示成 0 分 | `tests/test_rag_graph.py`（+6：检索器按图顺序出文章、没落点 refused、top_k、全局无主题如实说、返回体装图、缺字段不炸）+ 两侧契约（fixture 加 `enableGraph`/`graph`，Java DTO 同步） |
| E5-3 GraphRAG 的收益结论 | ⏳ **等用户环境** | 在真实额度下与 dense/sparse/hybrid 比；结论只能是「保留并接读者侧」或「删掉，不留半成品」。**判定规则已落地为代码**：`graph_verdict`（基线取最强非图行、阈值 2 个百分点、有上游失败或用 Fake 时判 inconclusive）+ `compare_strategies.py --graph` 一条命令打印结论 | `tests/test_graph_verdict.py`（7 条：有收益/无收益要写成动作/阈值内不算赢/上游失败不下结论/Fake 不下结论/缺图臂/缺基线各如实说） |
| **M4 增量索引（对账式）** | ✅ 判定与工具就位（**真跑需 Qdrant 隧道**） | `app/rag/index_reconcile.py`：不发事件（那要求 content-service 与 ai-service 同步调用，且事件丢了就永久不一致），而是**对账**——当前语料段落哈希 × 索引里已有哈希 → 不变 / 要重建 / 要删除。三条口径：① **拿不到哈希就当「变了」**（老数据缺 `contentHash` 或读取失败都按重建）—— 反过来假设「没变」会让改动**永远进不了索引**且不报错；② **只看内容哈希不看时间戳**；③ **「不变」的价值要报出来**（省下的就是这些嵌入）。配套 `QdrantVectorStore.hashes_by_post`（**只读 payload 不读向量**、滚动分页、读不到哈希记 None 而不猜）与 `scripts/index_reconcile.py`（打印计划；连不上 Qdrant 如实报错并指向隧道命令） | `tests/test_index_reconcile.py`（11 条：一致跳过、变了重建、新文章、**读不到哈希按变了处理且单独计数**、语料侧缺哈希不猜、多段整篇比较、消失的要删、语料清空、结果确定可复现、稳态明说、rebuildCount）+ `tests/test_qdrant_store.py`（+3 协议：只读 payload 不读向量、缺哈希记 None 且无 postId 跳过、**跟着 next_page_offset 翻页**） |
| **M6 压测工具** | ✅ 工具就位（**真实数字待 GPU/额度**） | `scripts/bench_provider.py` + `app/providers/benchmark.py`：按角色压 chat/embedding/rerank，输出 **TTFT / 生成速度 / P50·P95·max / 吞吐 / 显存**，可 `--json` 落报告。四条口径（都是压测最容易造假的地方）：① **失败请求不计入延迟分位**但单独计数（一次 30 秒超时混进 P95 会让分布看起来变差，而真相是「有一次根本没成功」）；② **非流式没有 TTFT**（首字与整段是同一时刻，编一个 ≈总延迟 的数字是最常见的假数据，这里留空并说明）；③ **没测到就是 None 不是 0**（`p50=0ms` 会被读成「快得不可思议」）；④ **显存读不到就给原因不给 0**。百分位用**最近秩法**（样本少时插值会造出「比任何实测都快」的数字） | `tests/test_provider_benchmark.py`（10 条：最近秩、空样本给 None、非法分位、**失败不进延迟分位**、非流式无 TTFT 且说明原因、流式算 TTFT 与生成速度（扣掉首字等待）、一次都没成功不算「快」、嵌入无 token 速度、并发体现吞吐、显存探测绝不用 0 撒谎） |
| **M8 检索审计 retrievalAudit** | ✅ | `deploy/sql/17_ai_retrieval_audit.sql` + `AiRetrievalAuditService`/`Controller`：`GET /ai/admin/retrieval-audit/summary?days=7` 给出总数、**拒答率**、**失败率**、被引用最多的文章、**被反复问的问题**（哈希相同 → 最直接的知识缺口信号）。四条口径：① **不存问题原文**（只存 SHA-256 与长度），要看原文/候选全文按 traceId 去看**进程内回放**；② **拒答与失败分开记**（拒答高=语料没覆盖、失败高=链路坏了，处置完全不同）；③ **候选数与引用数分开**（差距大说明「召回了一堆但没一条够格进答案」）；④ **记录 best-effort**（mapper 抛错也只记 warn，绝不影响问答）。⚠️ 场景缺失时记 `unknown` 而不是丢整条记录 —— 静默丢数据比记个 unknown 危险 | `AiRetrievalAuditServiceImplTest`（9 条，H2 真落库：只存哈希与长度、**拒答与失败互斥**、同一文章多次引用只算一次、没引用时最高分为空而不是 0、汇总的四个数与结论、空表如实说、**窗口边界**、**注入会抛错的 mapper 证明不往外传**、null 容错） |
| **B-10 ⑦ 接线：检索走向量库的开关（默认关）** | ✅ 代码完成，**验收未做**（需额度） | `assembly.pipeline_for` 新增部署期开关 `AI_DENSE_STORE_ENABLED`：关（默认）走内存通路、开则把 `QdrantVectorStore(QdrantConfig.from_env())` 作为 `dense_store` 交给 `RetrievalPipeline`。三条刻意口径：① **开关是部署期的**（模块导入读一次、并进管道缓存键）—— 运行中改环境变量不生效；这不是缺陷，而是为了「一个进程只持有**一个**向量库客户端」（管道是缓存的，缓存住管道即缓存住客户端，能来回切的开关会漏连接，故用进程级单例 `_shared_dense_store()` 惰性创建）；② **索引未验收前不要打开**（黄金集 30 题「不劣化才切」，没量过就打开等于把未验收通路接到用户面前）；③ **payload 过滤仍未做**，所以打开前必须确认索引里只有公开内容（今天的保证来自上游投影），且**千万不要先加过滤** —— payload 没有对应字段时 Qdrant 会**静默命中零条**，问答变成「没有依据」而日志一切正常。 | 已核实 `RetrievalPipeline(dense_store=...)` 的参数名（否则等于装了一个一开就崩的开关，而默认关闭时单测根本发现不了）。ruff ✅、mypy ✅（81 文件）、pytest **809 passed**。⚠️ 未做：payload 补 kind/可见性 + search 过滤 + 检索前调 `assert_model_fingerprint` + 黄金集验收 |
| **B-9 ⑦ 交接：检索切换的四件事与两条风险** | ✅ ①③④ 已做，② 仍缺（等 S4） | ④ 已完成的部分：指纹护栏已就位（写入侧补 payload、读取侧可比对）。**剩余四件，必须按序**：① 语料/切块 payload 补 `kind` 与可见性字段（今天语料只有文章、且全部公开，**但别硬编码成常量** —— 将来笔记接入时同一路径会写错）；② `QdrantVectorStore.search()` 支持 filter（只允许 published/public）；③ `assembly.pipeline_for` 在开关打开时传 `dense_store`，**开关必须进管道缓存键**（`(EPOCH, config, fingerprint)` 里没有它就等于「改了开关不生效」）；④ 检索前调用 `assert_model_fingerprint`。**两条风险（这正是我不在预算末尾动它的原因）**：⚠️ **对不存在的 payload 字段做过滤 = 一条都命中不了**（Qdrant 对缺失字段不匹配），所以 ① 必须先于 ②；⚠️ 每个管道持有一个 httpx 客户端，缓存住管道就等于缓存住客户端，切换开关时必须能把它关掉（否则连接泄漏）。**验收门槛**：黄金集 30 题比「内存通路 vs 向量库通路」的 Recall@1/NDCG@5，**不劣化才切**；跑一次五组对比要几十次嵌入调用，免费档（每模型每日 50 次）很可能不够 —— 所以这项验收需要额度，代码可以先合但必须**显式标记「未验收」**。 | 本轮只做核验：工作区干净、Python ruff ✅ / mypy ✅（81 文件）/ pytest **809 passed**、后端 mvn **exit 0**。**2026-10-05 更新**：① payload 补 `kind`（助手 S1）与 ④ 检索前校验 `modelFingerprint`（切向量库那一刀）**都已完成**，③ 早已在 B-10 落地；**② `search()` 的 filter 仍未做** —— 当前索引内容全部来自投影表（只有公开内容），可见性不依赖它，等 S4「按 kind / 标签 / 作者收窄范围」时才需要，且必须遵守「先写字段、再过滤」。详见 §4.2 与 §2 的「dense 检索切到向量库」一行 |
| **B-8 ⑦ 前置：嵌入模型指纹护栏** | ✅ | ① 写入侧：`QdrantVectorStore.point()` 往 payload 里补 `modelFingerprint`（这条向量是**哪个模型**建的）—— 取不到指纹就**不写字段**（离线单测/未装配 Provider 时，护栏不该把「能不能写库」一起挡掉）。② 读取侧：`sample_model_fingerprint()`（滚动 1 条只读 payload）+ `assert_model_fingerprint(expected)`：**索引为空或旧数据没这字段 → 放行**（不能因为「没记录」挡住第一次写入）；有记录且不一致 → **抛 `ProviderError`**，消息给出「换嵌入模型必须整库重建」与重建入口。为什么必须做：维度不同 Qdrant 会直接失败（天然保护），但**同维度不同模型不会失败**，只是结果全错 —— 表现是「检索质量突然很差」，而人会先怀疑切块/提示词/rerank。 | `tests/test_qdrant_fingerprint.py` 4 条（读到指纹、空索引不算不一致、不一致抛错且消息含修复路径、一致放行）。ruff ✅、mypy ✅（81 文件）、pytest **805 → 809**。⚠️ 尚未接线：⑦ 的检索切换（开关 + payload 过滤 + 在检索前调用护栏）仍未做 |
| **B-7 ⑥ 收尾：Java 触发 + 定时对账** | ✅（定时默认**关闭**，要显式打开） | ① 契约补 `INDEX_RECONCILE = /admin/index/reconcile` + Feign `reconcileIndex()`（返回 `Map`）+ `POST /ai/admin/index/reconcile`（ADMIN 透传）；② 新增 `IndexReconcileJob`：`@Scheduled` 调对账，**默认关闭**（`stellar.ink.index.reconcile-enabled`）—— 理由写进注释：投影同步只读清单不花钱，而对账一旦有变更就要**真调嵌入模型**，「装好就自动开始花钱」不该由我替用户决定；间隔默认 10 分钟、首次延迟 2 分钟，`fixedDelay`（避免慢时堆叠出并发对账）。③ Python 侧成功响应改成**裸数据**（与 `/eval/*` 同形），否则 Java 的 Map 收到的是「壳里的壳」。 | 后端 ai-service 247、content-service 42，BUILD SUCCESS（新 controller/job 不需要加 @MockBean —— 12 个切片本就 mock 了 PythonAiClient）；Python ruff ✅、mypy ✅（81 文件）、pytest **802 passed**。⚠️ 又踩到 FastAPI 的响应模型推断：`dict | JSONResponse` 与 `-> dict[str,int] | JSONResponse` 都会让采集期报 Invalid args for response field（13 个测试文件全挂），最终用 `-> Any` + `response_model=None` 解决。未做：`scene=index` 调用账（需要给台账「系统发起」的语义） |
| **B-6 对账式增量索引（Python 端点）** | ✅（Java 触发与定时任务待补） | 新增 `POST /admin/index/reconcile`：语料 → `store.hashes_by_post()`（**只读 payload，不读向量**）→ `reconcile()` 差异计划 → 只重嵌「新增/变更」的、删掉「语料里已经没有的」（`IndexPipeline.index(changed_posts, removed_post_ids=plan.removed)`）。为什么增量靠**对账**而不是事件：事件会丢（重启/抖动/并发写），漏一次就永久不一致，而对账每轮自愈 —— 纯逻辑早就在 `index_reconcile.py`（11 条用例），本刀只接线。返回 `{unchanged, reembedded, removed, chunksWritten, deletedPosts, embedCalls}`。⚠️ 未做：① Java 侧触发入口（契约里没有 reconcile 路径，要新增常量+Feign+controller）；② 定时任务；③ **调用账 `scene=index`** —— 嵌入调用在 Python，而台账由 Java 写（身份/配额/场景在那边），本刀只写日志。 | ruff ✅、mypy ✅（81 文件）、pytest **802 passed**（含 `EXPOSED_PATHS` 白名单断言）。过程中又一次踩到 PowerShell 引号（用 `\"` 转义 → 解析期整条命令失败，什么都没执行），改为字符串拼接后正常 |

| **B-5 ④ 接线：清单带 tags/authorId → 投影加两列 → Python 切到线上语料** | ✅（需跑 `19`(新装) 或 `20`+`21`(已跑过旧版) 并同步一次） | ① `CorpusItemVO` 补 `tags`/`authorId`（写作画像要用，见下），`InternalCorpusServiceImpl` 从 `Post/Note.getTags()/getUserId()` 填充；② `ai_content_snapshot` 加 `tags`/`author_id` 两列（`21_*.sql` 幂等 ALTER，`19_*.sql` 同步补上供新装库）；③ 同步写入并在用例里断言；④ **Python 真正切换来源**：`corpus.py` 先读 `ai_content_snapshot`（只碰 `ai_*` 表），读不到（迁移没跑/缺列/连不上）才回退种子包，`corpus_source()` **如实说明是哪一种**；表存在但为空时**不回退**（那是「站内确实没有公开内容」，回退会把已删文章重新拿来回答）。⑤ `PostLike` 协议扩上 `tags`/`author_id`（否则「管道能用、画像用不了」的半可用状态只能靠运气），`app/api/v1/style.py` 的注解随之改成协议类型。 | 后端：content-service 42、ai-service **247**（投影用例断言 tags/authorId），BUILD SUCCESS。Python：ruff ✅、mypy ✅（**它正好在 `style.py:44/56` 报出 `PostLike` 缺 `author_id`/`tags`** —— 这就是上一刀定位的那处耦合，被类型系统抓住了）、pytest ✅（新增 `test_content_source.py` 4 条：明文不过度反转义、字段与 SeedPost 对齐、缺 MYSQL_* 报可用性、表不存在时文案指向脚本与 `/corpus/sync`） ⚠️ 未做：真实往返（需跑 SQL + 重启 ai-service 同步 + 重启 Python）；笔记仍不在语料（`kind+id` 与 `postId` 契约冲突，需单独一刀） |
| **B-4 语料来源模块（未接线）** | ⚠️ 未接线（前置缺口已定位） | 新增 `app/rag/content_source.py`：读 `ai_content_snapshot` 的只读来源模块（`SnapshotDoc`，字段与 `SeedPost` 对齐：`post_id/title/plain/tags/author_id`；**刻意不复用 `SeedPost`** —— 它的 `plain` 会做 SQL 反转义（`\\n`→换行、`'`→`'`），而库里正文是明文 Markdown，复用会让检索原文与读者看到的原文不一致）。失败一律翻成 `CorpusSourceUnavailable`（表不存在时报错文案直接给出「跑哪个脚本 + 怎么触发同步」）。⚠️ **接线被两个缺列挡住**：`app/api/v1/style.py` 需要 `post.tags` 与 `post.author_id`，而投影表**两者都没有**（`/internal/corpus` 的清单也不带）→ 直接切换会让写作画像运行时 `AttributeError`。所以本刀**回退了 `corpus.py` 的切换**、保持种子包不动生产行为；补列顺序：清单 VO 补 `tags`+`authorId` → 投影加两列（21_*.sql）→ 同步写入 → 再切来源。 | `tests/test_content_source.py` 4 条（明文不过度反转义、字段与 SeedPost 对齐、缺 MYSQL_* 报可用性、表不存在时文案指向 19/20 脚本与 `/corpus/sync`）。ruff ✅ mypy ✅（80 文件）pytest ✅ |

| **B-3b 投影自足：正文进 ai_content_snapshot** | ✅（`19_*.sql` 已含 `content` 列；跑过旧版 19 的需再跑 `20_ai_content_snapshot_content.sql`） | 上一刀把投影设计成「不存正文、嵌入时按需向上游单篇取」，但 **Python 只允许读 `ai_*` 表**、没有别的途径拿正文 → 投影不自足，索引无法切块。本刀改正：正文进投影（派生数据、可整表重建；换来「索引构建不依赖 content-service 在线」）。同步**只为新增/变更的文档回取正文**（`docHash` 没变就沿用已存的），不会每轮搬整库正文。并发从严：**清单里有、正文却 404**（刚转私有/刚下架）→ 跳过 + 清旧行（`skipped` 单独计数），绝不沿用旧正文。 | `CorpusSyncServiceImplTest` 7 → **11** 条（只为变更的文档回取正文、插入的行带正文、404 视为不可公开并清旧行、取正文传输失败整轮失败不清库）。ai-service 243 → **247**，BUILD SUCCESS。**测试抓到两个真 bug**：① `ErrorCode.getCode()` 是 `Integer`，用 `==` 比较装箱值（404 超出 `Integer` 缓存区间）→ 该分支与告警日志**永不生效**，改 `equals`；② 回取正文的传输失败会**抛出** `sync()`（管理端点变 500），违反「不抛异常、如实回 failed」契约 → 整个比对循环兜进 try，并抽出统一的 `failed(...)` 出口 |
| **B-2/B-3 语料投影：Java↔Java 契约 + ai_content_snapshot + 对账式同步** | ✅（需执行 `19_ai_content_snapshot.sql`） | ① 新模块 `stellar-ink-content-client`：`ContentCorpusClient`（Feign，复用 shared-model 的语料 VO，不在客户端再复制一套 DTO）。**两个刻意的偏离，都写进了注释**：(a) 用 `url = ${stellar.ink.content.base-url}` 而**不是**服务发现 —— dev/test 共用同一 Nacos 命名空间，`name=content-service` 可能把本机 ai-service 路由到**测试机**实例（另一个库、另一批文章），表现为「同步过来的语料跟本机库对不上」且日志无异常；(b) **没有 FallbackFactory** —— 本模块没有 circuit breaker 依赖，Spring Cloud OpenFeign 会**静默忽略**它（仓库里已有一个因此被删除的 `PythonAiClientFallbackFactory`），失败处理改由调用方显式做。② `deploy/sql/19_ai_content_snapshot.sql`：投影表（`kind + content_id` 唯一键做幂等锚点；正文见下一刀 B-3b —— 最初设计成不存正文，后被 Python 的边界倒逼补上 `content` 列）。③ `CorpusSyncServiceImpl`：全量拉清单 → 逐条比对 → 增量写入 → **删除上游不再返回的行**（下架/已删/**笔记转私有**都走这条路）；拉取失败**一行都不删**、只如实回 `failed+reason`；上游返回空则删除全部（**隐私优先于「重新嵌入」这个可恢复成本**），删掉超过一半时打 WARN 让人能在日志里看见。④ 定时任务（默认 5min，`fixedDelay` 避免堆叠；开关/间隔全在配置）+ `POST /ai/admin/corpus/sync`、`GET /ai/admin/corpus/count`（ADMIN，经网关为 `/ai/admin/corpus/**`）。 | `CorpusSyncServiceImplTest` 7 条（首同步全插入、只更新哈希变了的、**删除上游缺失的行**、拉取失败不清库、分页游标续拉、游标不推进不死循环、上游为空删全部）＋ `AiCorpusControllerTest` 2 条（未登录被拒）＋ **12 个既有 @WebMvcTest 切片各补一个 `@MockBean CorpusSyncService`**（新增 `@Service` 会让切片去建 Feign 客户端与 Mapper —— AGENTS 记过这条；实测 12 个切片全红后按处方一次改完）。ai-service 234 → **243**、content-service 42、common-core 62，BUILD SUCCESS。⚠️ 未做：真实往返（需先跑 `19_*.sql` 并重启 ai-service，才能 `/ai/admin/corpus/sync` 打通 content-service）；Python 语料来源切换（下一刀） |
| **B-1 语料来源：content-service 内网只读口子** | ✅（本刀） | 新增 `/internal/corpus`（清单，不含正文）与 `/internal/corpus/{kind}/{id}`（单篇正文）。**可见性规则只有这一份**：文章 `status=1`；笔记 `status=1` **且** `visibility='PUBLIC'` —— 草稿与私有笔记在清单里不出现、按 id 取正文也一律 404（两道闸门）。`docHash` = 整篇（标题+正文）SHA-256（`common-core` 的 `Hashes`，规范化换行以免「编辑器换了换行符」触发无谓重嵌）；⚠️ 它与索引 payload 里**每个子块**的 `contentHash`（Python 切块时算）**不是一回事**，命名刻意区分。清单支持 `since`（**严格大于**，游标续拉）/`ids`/`limit`（默认 500、上限 1000），截断显式回 `truncated`（否则对账会把「没给全」当成「这些文章被删了」去删索引）。**网关无任何 `/internal/**` 路由**，从外面访问是 404。 | `InternalCorpusServiceImplTest`（9 条：只查已发布/公开、私有笔记取不到、截断如实上报、`since` 是 `gt` 而非 `ge`、内容变更改变 `docHash`、空语料返回空表）＋ `HashesTest`（3 条，含与 `sha256sum` 的已知值对齐）。**真实数据核对**：本地库 文章已发布 29 / 笔记已发布且公开 11 / 笔记已发布但私有 **2（必须排除）** → 语料将由「种子包 29 篇」变成「29 文章 + 11 公开笔记 = 40 篇」。⚠️ 未做：真实 HTTP 往返（jar 被运行中的服务锁住，需重启后才能 `curl` 这两个端点），以及 Java↔Java 契约模块、投影表（后续刀） |
| **Qdrant 地址可配置 + 两处「说了不存在的东西」的更正** | ✅ | ① `QdrantConfig.from_env()`：`QDRANT_BASE_URL` / `QDRANT_API_KEY` / `QDRANT_COLLECTION`，**默认值一个都不变**（`http://127.0.0.1:6333`）—— 不配还是「本机/SSH 隧道」，配了就能直连测试机或云；脚本里**必须显式 import `app.core.config`** 触发 `.env` 加载，否则 `QDRANT_*` 静默不生效。② **更正一处我说错的话**：`app/` 里**从来不构造 `QdrantVectorStore`** —— `assembly.pipeline_for` 不传 `dense_store`，`RetrievalPipeline.prepare()` 在 `dense_store is None` 时走**内存通路**（整库嵌入一次 + 本地余弦，嵌入有缓存）。所以「Qdrant 没起 → dense 检索会报错」是错的：**应用今天不碰 Qdrant**，只有 `scripts/qdrant_smoke.py` 与 `scripts/index_reconcile.py` 会连。③ **索引重建入口确实缺失**（契约层齐全：Python schema、Java DTO、`AiContractPaths.INDEX_REBUILD`、Feign `rebuildIndex`、roadmap 写着 `POST /ai/admin/index/rebuild`；但 Python 无该路由、Java 无 controller、`app/` 里没有任何地方构造 `IndexPipeline`）—— 属于 M4 遗留缺口，`index_reconcile.py` 原先那句指向该接口的提示已改成如实说明 | `tests/test_qdrant_store.py`（+4：不配保默认、直连远端、去尾斜杠与空白、坏协议当场报错）；**实测直连**：`index_reconcile.py` → 测试机 v1.19.0、语料 41 子块/29 篇、索引 0 篇（集合不存在如实说明）；`qdrant_smoke.py` 11 步全过（建集合→写点→检索→阈值拒答→按文章删点→端到端索引→检索→清理） |
| **M12-S6 知识库维护 + 条目数（第三优先）** | ✅（需执行 `13/14/15_ai_wiki.sql`） | AI 实验室新增「知识库」页签，顺序是「先知道有多少 → 再知道坏在哪 → 最后才动手重建」：① **规模**用盘点的 `checked`（也是目前唯一能拿到的全站规模）——实测确认 `/ai/wiki/claims/count` **需要 postId**，是**按文章**计数，所以界面写「扫到 N 条」而不是含糊的「全站 N 条」，阅读页的「本文 N 条」直接用已加载的 claims 长度（**不**再打那个接口，否则是重复取数）；② **盘点**分 current/stale/orphan 三类 + 两类 id 列表，且明确「**盘点不自动重建**」（合成一个按钮会让人以为点了就等于修好了）；③ **截断警告** `truncated=true` 时单独醒目（只显示「0 条过期」是最危险的误读）；④ **构建**把「落库侧的数」（inserted/updated/skipped）与「模型侧的账」（proposed/kept）**分开显示**，`dropped` 按原因分解（最主要那条是「引用没能在原文里找到」），表单留空 = 服务端决定范围、**不写「全量重建」** | `scripts/ai-ops-selfcheck.mjs` 扩到 **64 条**（新增：三类之和等于扫到条数、截断被识别且 notes 带回来、盘点失败时不给「0 条过期」这种误导性零、落库数 vs 模型账分开、dropped 按原因排序、**表单留空不带 maxPosts**、中英文逗号都能分 id、构建失败清空上次结果）—— 已并入 `npm run check` |
| **M12-S5 检索审计 + 链路回放（第二优先）** | ✅（需执行 `17_...sql`） | AI 实验室新增「检索与回放」页签：上半是**趋势**（拒答率/失败率/被引用最多/被反复问的问题），下半是**单次回放**（调用账 + 检索候选，支持 `?tab=retrieval&trace=xxx` 直接带参进来 → 「报错 → 看回放」闭环）。四条口径：① **拒答率与失败率分开**（语料没覆盖 vs 链路坏了）；② **读不出来 ≠ 没人用过** —— 审计表没建时也是「读不出来」，所以界面自带静态指引（`AUDIT_HINT` 指出要跑 `17_...sql`）而不是指望后端 msg；③ **回放查不到不能说「不存在」**（回放是 Python **进程内**的，多副本时会落别的副本；文案是「这个副本上没有」，调用账仍有效），且「Python 不可达」与「副本上没有」分开说；④ **Map 的字符串键要归一**（Java `Map<Long,Integer>` 序列化键是字符串，不归一 `postId` 会是 `"7"`）。复现问题只有哈希（审计不存原文），界面照实解释并把 traceId 指过去 | `scripts/ai-ops-selfcheck.mjs` 扩到 **48 条**（新增：字符串键归一、零记录时拒答率为 null、**后端 notes 两种可能照实显示**、审计失败给静态指引、回放未命中时措辞不含「不存在」、Python 不可达与副本上没有分开、空输入不发请求）—— 已并入 `npm run check` |
| **M12-S4 运行时装配状态 + 用量与成本（第一优先）** | ✅ | AI 实验室新增两个页签。三条口径都是「不让面板说出它证明不了的话」：① **「面板已配」≠「真的生效」** —— 能看到的是「配置行存在 + 启用 + Python 可达」，而 Python 是否真装配了这份配置只有在**一次真实调用**里才能证明，所以文案写「已配置」不写「运行中」，并在面板顶部明写这句 caveat；② **连通性自检只测 TCP**（`scope: tcp_only`）—— 端点连得上不代表模型名与密钥可用，卡片上照实写；③ **成本为 0 不等于免费** —— 没单价的调用按 0 计（`unpricedCalls`）、没 token 数的连算都算不了（`untokenizedCalls`），这两个数不显示出来会让人以为 AI 不花钱。细节：失败率一次没跑过时显示「—」而不是 0%（0/0 不是 0%）；缺角色时**同时**说「缺哪几个」与「没配会怎样」；三档状态有可见差异；探活失败不让配置那块一起失败 | `scripts/ai-ops-selfcheck.mjs`（28 条，含「措辞是已配置而不是运行中」「探活失败不影响配置读到了这个事实」「0/0 不是 0%」「成本为 0 时给出两条解释」）—— 已并入 `npm run check` |
| **M12 个人 AI 模型配置** | ✅（需执行 `18_...sql`） | 读者/作者在**账号页**配自己的模型，不只是站长。三条硬约束：① **只放开 chat/fast/reasoning** —— `embedding`/`rerank` 不按用户隔离（向量索引只有一份，换嵌入模型检索得到的是**错的**结果），Java 写入期直拒 + Python 忽略并警告（双保险）；② **地址只允许公网**（SSRF）：`base_url` 是服务端去请求的地址，站长那份允许内网（自建 vLLM 就在 127.0.0.1），用户那份拒绝 loopback/私网，**链路本地两档都拒**；域名会尽力解析一次挡「公网域名指向 10.x」；③ **身份只从会话来**（请求体里的 userId 无效）。实现上服务层**作用域化**（`*Scoped(ownerId,…)`）而不是复制实现；`runtimeConfigs` 加 `user_id = 0` 过滤防「个人模型变成全站默认」；网关 `/ai/me/**` 与 `/ai/memory/**` 合并成同一条「登录即可」且必须先于 GET 全放行。迁移用 `user_id = 0` 而不是 NULL（MySQL 唯一索引里 NULL 互不相等，「一角色一行」会静默失效） | Python：`tests/test_url_policy.py`（22 条）+ `tests/test_provider_user_scope.py`（9 条，含「端点必须把身份里的 user_id 传下去」「用户配的 embedding 必须被忽略并警告」）；Java：`ProviderUrlPolicyTest`（18）+ `AiProviderUserScopeTest`（7，H2 真落库：两用户同角色不撞键、listMine 只回自己、**个人行不进 runtimeConfigs**、删除回落全局、embedding/rerank 被拒、内网地址用户拒站长放行、未登录不许写）+ 网关门槛 1 条；前端：`scripts/my-models-selfcheck.mjs`（23 条） |
| **M6 Provider 契约测试 + 降级策略** | ✅（真机压测待硬件） | ① **契约测试**：一份用例、两个实现（`fake` 与 `openai_compatible` + MockTransport；后者就是 vLLM/SGLang 暴露的协议，所以这份用例原样能打到本地推理服务上）。它**立刻抓出两处 provider 行为不一致**：空嵌入输入一边抛错一边返回空、`top_n` 只转发给上游（上游忽略就超条数）。定契约：**空输入是业务语义不是协议错误**（空进空出、不调上游、维度记 0 不猜）+ **重排自己排序再截断，不指望上游听话**。② **降级**（`app/providers/fallback.py`）：白名单 `FALLBACK_ALLOWED_SCENES`（qa/writing/agent/chat），**会被存进库或拿来比较的场景（wiki/eval/memory/index）宁可失败也不换模型** —— 换模型的后果是「同一次构建混进两种笔迹，而两种笔迹在库里长得一样」；降级后 `usage.model` 记**备用模型**并留一条 `FallbackNote`（把主模型名字留着等于让降级查不出来）；**只对可重试错误降级**（401/额度用尽不降级） | `tests/test_provider_contract.py`（15 条：chat 响应完整、**嵌入顺序与输入一一对应**、空输入不炸、重排降序且下标合法、top_n 截断、四种状态码的错误分类与可重试性一致、未知路径不静默返回空）+ `tests/test_provider_fallback.py`（11 条：成功不碰备用、瞬时可降级且标明谁答的、**四个高风险场景绝不换模型**、401/额度用尽不降级、瞬时限流降级、备用失败抛备用自己的错误、记录可累积） |
| **M8 Prompt Registry** | ✅ | `app/prompts/`：提示词的**名称 / 版本 / 变量 / 适用角色 / 输出形状 / 评测状态**。三件事一起做才有意义：① **模板留在各自模块里，注册表只做索引**（反过来会让用到提示词的模块都依赖注册表、而注册表又依赖它们，绕成一圈；装配惰性化绕开 import 期依赖）；② **变量显式且启动自检**（声明的与模板里实际用到的必须一致，否则一渲染就炸、且常炸成与真因无关的 KeyError —— E4 踩过）；③ **`evaluation` 如实留白**（没评测过就是 null，显示「尚未评测」；编一个「效果良好」比留白危险得多）。`GET /prompts` 只出元数据不出模板全文。⚠️ **自检当初就是被 `agent.system` 顶出来的**：它里面的 JSON 示例花括号在 `str.format` 眼里就是字段名，所以「原样发送」的提示词标 `interpolated=False`，自检不解析它 | `tests/test_prompts_registry.py`（15 条：真实目录自检通过、覆盖六个在用提示词、**注册的模板就是模块里那个常量**、key 带版本、未知名/版本报错、渲染缺变量与多变量都报错、静态提示词不被当模板、`{a!r}/{a:>4}/{{}}` 的变量解析、重复注册被拒、自检报出两类不一致、评测状态如实留白、describe 不含模板全文、装配是纯函数、端点返回元数据、端点需签名） |
| **M8 熔断（第三条护栏）** | ✅ | `app/providers/circuit_breaker.py`：按 key（**角色 + 模型 + 端点**）隔离的断路器，接在 provider `_post` 的**重试之前**（打开时连第一次尝试都不做，否则「熔断」只是少重试几次）。四条口径：① **只统计连续失败**（中间成功一次就清零，否则偶发失败会攒成熔断）；② **配置类错误不进熔断**（401/400 不会自己好，进熔断只会给「去面板改 Key」多隔一层迷雾）；③ **额度用尽用长冷却**（免费档每日 50 次，退避几秒救不了；短冷却等于每 30 秒打一次注定失败的请求）；④ 冷却结束**只放一个探针**，探针失败则冷却翻倍（有上限）。打开时抛的错误**带上上一次失败的真因**（否则用户看到「服务暂不可用」，而「额度用尽/模型名写错」被这层吃掉） | `tests/test_circuit_breaker.py`（13 条：连续失败才打开、成功清零、真因随错误透出、半开只放一个探针、探针成功关闭、探针失败冷却翻倍、配置类错误不打开、额度用尽长冷却、瞬时限流要够阈值、**按 key 隔离**、快照只列异常 key、非法配置被拒、**集成：熔断打开后上游调用数不再增长**） |
| **M9-4 前端记忆面板** | ✅ **M9 收口** | 账号页新增「AI 记忆 · 星笺记住了我什么」面板（**默认收起、展开才取数**：账号页是常来页面，不该为一份多数时候没人看的列表每次都发请求）。三块分开：**待确认**（候选还没生效，逐条或全部确认）、**需要你决定**（冲突两地正文对照，明确写「没有自动覆盖」）、**我的记忆**（启用/禁用/删除）+ 风格画像（刷新/版本/生成时间）+ 全部清除（**先确认再清**，不可撤销）。⚠️ 与 Wiki 面板**刻意相反**：Wiki 取不到要静默，记忆取不到**必须说出来** —— 用户主动来看时一无所获，他会以为记忆丢了或自己从没记过 | `scripts/memory-selfcheck.mjs`（30 条：两份列表分开取、**取数失败要标记失败**、失败清空旧数据、丢弃原因写进提示、**冲突单列且带上两地正文**、写成功就地生效、**写失败回滚本地**、删除失败那条要回来、清除连冲突清单一起清、画像「还没生成过」≠失败、四个标签函数含兜底与「用户确认不能说成原文」）—— 已并入 `npm run check` |
| **M9-3b 派生风格画像落库** | ✅ | `AiStyleProfileService`：按登录者的文章刷新画像并**存成新版本**（`ai_style_profile`，版本递增）+ `GET /ai/memory/style-profile`（没有时 data 为 null ——「还没生成过」与「生成出来是空的」不是一回事）。它落库的理由就是 M9 验收那句「删除后同步清除派生风格画像」：**清一个不存在的东西，看起来永远是对的**。⚠️ **样本不足时不落库**（Python 回 `evidenceSufficient=false`）：存一版「样本不足」的画像看起来像正常画像，下游会拿它当「这位作者的风格」用；判断顺序也要紧 —— 先判 `profile` 为空会把「多写几篇就好了」说成「生成失败，请稍后重试」，让用户反复重试一个不会变好的请求 | `AiStyleProfileServiceImplTest`（6 条：版本递增且取最新、**样本不足不落库**、未生成过返回 null、按登录者自己的文章统计、**删除记忆确实清掉画像**、**全部清除也清画像**） |
| **M9-3 召回接线（问答/Agent 用上记忆）** | ✅ | 记忆真正进提示词：Java `listRecallable` 按**登录身份**取自己的 active 记忆 → Python `/memory/recall` 过滤排序 → 正文进 `/qa` 与 `/qa/stream` 的 `memories` 字段。**关键口径**：记忆是**语气与取舍**的参考，不是证据 —— Python 侧 `MEMORY_PROMPT` 明确要求「不是文章内容、不得当事实陈述、不得编号引用、与摘录冲突以摘录为准」（混起来的具体表现是模型把「作者喜欢短句」写成「文章里说他喜欢短句」）。没有记忆时不调 Python（最常见路径不多一次内网往返） | Python `tests/test_qa_memory_prompt.py`（6 条：无记忆时没有该段、记忆段在摘录之前、警告含「不是文章内容/不得编号引用/以摘录为准」、系统提示词的「只依据摘录」未被削弱、结尾仍是引用指令）+ Java `AiMemoryRecallTest`（4 条：**只送自己 active 的**、无 active 时不调 Python、按 Python 给的顺序取正文、过期时间只传有值的） |
| **M9-2c 抽取→待确认→确认** | ✅ | `POST /ai/memory/extract`（抽候选**落成 pending**，不参与召回）+ `POST /ai/memory/confirm`（Java 把「已生效记忆 + 待确认候选」交给 Python `/memory/plan`，再按三份清单执行：**新增→active**（来源记 `user_confirmed`、可信度提到 0.9）、**重复→把新证据补到已有那条、删掉待确认那条、已有正文一字不改**、**冲突→保持 pending 并原样报回**）。⚠️ **幂等锚点必须含状态**：原设计下 pending 与 active 无法共存，于是「候选与已生效记忆重复」这个必然常见的情况有两种难查的表现 —— 抽取直接 DuplicateKey（用户看到 500 而不是「这条已记过」）、确认的合并分支永不触发（**是 H2 的唯一键把这个问题顶出来的**） | `AiMemoryConfirmTest`（7 条：抽取落 pending 且证据落库、确认转 active 并提可信度、重复只补证据不改正文、冲突两行都不动、只确认点名的那些、无待确认如实说、同段对话抽两次不膨胀） |
| **M9-2b 记忆落库与 Java API** | ✅ | `deploy/sql/16_ai_memory.sql` 三张表（`ai_memory` 幂等锚点、`ai_memory_evidence` 一条记忆多份出处、`ai_style_profile` **带版本**的派生画像）+ pojo/mapper/service/controller：列表（带证据）、启用/禁用、删除（软删 + 清证据 + 清画像）、全部清除（硬清）。**删除走独立入口**：`setStatus` 传 `deleted` 被拒（code=1001）——状态接口绕过清理会让「界面上干净了、数据还在」。网关加 `/ai/memory/**` 登录门槛并**放在 GET 全放行之前** | `AiMemoryServiceImplTest`（9 条，H2 真落库）+ `AiMemoryControllerTest`（8 条：身份取自登录态、证据回前端、删除与清除、未登录拒绝、路径里没有 userId、状态参数必填、空列表不是错误、业务异常原样透出）+ `SaTokenConfigureTest.shouldRequireLoginForAiMemoryOnEveryMethod` |
| **M9-2a 记忆的 Python 出口** | ✅ | `app/schemas/memory.py` + `app/api/v1/memory.py`：`/memory/candidates`（抽候选并立刻校验）、`/memory/plan`（新增/重复/冲突）、`/memory/recall`（可召回 id）。两条边界写进契约：**请求体里没有 `userId`**（身份只从签名头来，用户隔离由 Java 的取数范围保证）、**出处校验只做一次**（计划层拿不到上下文，因此不重新校验、也不假装校验）。`EXPOSED_PATHS` 同步 | `tests/test_memory_api.py`（7 条：请求体无 userId、三端点需签名、召回过滤与排序、空结果要说清、冲突只报告、**计划层不重新校验出处**、超长对话 422） |
| **M9-1 作者记忆的规则层** | ✅ | `app/rag/memory.py`：模型只产出**候选**，能不能变成记忆由规则与用户确认决定。三条纪律：① 每条候选的 `quote` 必须**真的出现在这次上下文里**（用户自己说的话也算证据，同样要在上下文里），编造出处的直接丢并按原因计数 —— 这就是 M9 验收「模型不能在没有证据时把推测写成永久用户事实」；② 写入计划分**新增/重复/冲突**三份清单，**冲突不自动覆盖**（同义改写与「作者改了主意」都不该被静默盖掉）；③ 敏感信息（手机号/邮箱/证件号/银行卡/密钥/口令）**宁可误伤**——记忆会被召回进提示词。可选度取 `max(Jaccard, 包含度)`：只用 Jaccard 抓不住「长句里换几个字」的改主意（实测 0.45 vs 包含度 0.69） | `tests/test_memory.py`（19 条：无出处丢弃、用户明说也需在上下文、类型/长度/可信度门槛、敏感五类、同批去重、模型可信度封顶、重复只补证据不改正文、改主意判冲突、禁用后不再算冲突源、相似度含标点无关与已知弱区、**A 的记忆不会被 B 召回**、禁用/删除/待确认都不召回、类型与可信度与过期过滤、排序确定可复现） |
| **助手前置 P1/P2 + S1/S2（笔记进检索语料）** | ✅（需重启 Python；索引要重建一次才含笔记） | 用户要的「按笔记 / 随笔问答」在 S1 之前**做不到**：笔记数据早在投影表里（`kind='note'`），但 Python 读语料时写死 `WHERE kind='post'`，所以问笔记只会得到「没有找到依据」。四刀：① **P1 流式配额收口** —— `AiQaStreamController` 此前**只记账、从不检查**（`around`/`acquireQuota` 都没走），而前端默认走流式，于是调用数 / token / 角色 / 并发四道闸门对读者全是空话（「预算用尽」承诺了一个不存在的闸门）；改为开流前 `acquireQuota`、`finally releaseQuota`（正常结束 / 上游失败 / 客户端断开三条路一起放），触顶在**开流前**回 429、一个下游调用都不发。② **P2 语料 TTL** —— 语料是进程级永久缓存，唯一换代入口 `reset_corpus()` 在生产路径**没有任何调用者**：新发布的内容问不到，而**下架 / 已删 / 笔记转私有**的内容会继续被回答（后者是隐私问题，不是新鲜度）；加 `AI_CORPUS_TTL_SECONDS`（默认 60s，`<=0` 永不过期）并在到期时**换代**（`EPOCH++`）而不只是重读 —— 只清语料缓存会让检索管道拿着旧下标、引用指到另一段文字；管道缓存同时按当前 EPOCH 淘汰旧条目，否则每轮 TTL 多留一份语料在内存里。③ **S1 文档标识升级为 `(kind, id)`** —— 取数（`kind IN ('post','note')`）/ 切块 / `chunk_id` / payload / Qdrant 删除 / 对账全链路；**文章的 `chunk_id` 刻意保持历史格式**（点 id 是 `uuid5(chunk_id)`，改前缀等于一次没有收益的全量重嵌，而免费档一天 50 次嵌入跑不完），笔记写 `note-11`；删除文章的过滤**必须额外带 `is_empty(kind)` 那一支** —— 笔记接入之前的点没有这个字段，而 Qdrant 对**缺失字段的 `match` 一条都不命中**，漏了它升级后第一次重建一条旧点都删不掉。④ **S2 契约** —— `Citation.kind`（缺省 `post`）与 `IndexRebuildRequest.contentKind`；前端 `openCitation` 按 kind 跳 `/note/:id`、引用卡片标「笔记」；顺手修掉**流式 401/429 静默失效**（抛的必须是 `ApiError`，`isAuthError`/`isRateLimited` 才认得出来）。⚠️ 连带口径：**LLM Wiki / 写作画像 / 评测显式钉在 `ARTICLE_KINDS`** —— 它们的表里存的是裸 `post_id`（`ai_wiki_claim`、`ai_memory_evidence`、黄金集 `expectedPostIds`），笔记混进去会与**同号文章**互指；问答与 Agent 反过来用**全部语料**（那才是这一刀的目的） | Python：`tests/test_corpus_ttl.py` **6 条**（含**隐私验收**：上游转私有后语料与切块结果里都必须没有它）+ `test_index_reconcile.py` **16**（含「文章 3 与笔记 3 是两个文档」「转私有要从索引删掉」）+ `test_qdrant_store.py`（删除按 kind+id、老点按「缺 kind」算文章、同号文章/笔记的哈希分开）+ `test_index_pipeline.py` / `test_qdrant_integration.py`（端到端：删文章不误删同号笔记；fake 忠实实现 Qdrant 的 `is_empty` 语义）；Python **ruff ✅ / mypy ✅（81 文件）/ pytest 全绿**。Java：`AiQaStreamControllerTest` **9**（新增 3 条配额用例）+ `AiContractTest` **27** + `AiAgentControllerTest` / `AiQaControllerTest`，**BUILD SUCCESS**。前端：`npm run check`（自检 + build）全过。语料实测：**29 篇文章 + 11 篇公开笔记**（39 / 53 个子块） |
| **助手 S3：浮层 + 多轮（history）** | ✅ | 用户要的是「右下角浮层」，而 `stores/qa.js` 是**单一全局 store** —— 浮层与深读页面板会同时存在，共用一份 `answer` 时一边的 `delta` 会写进另一边的界面。所以先把状态机抽成**可多实例**的 `composables/useQaStream.js`（`stores/qa.js` 退化成一层薄封装，深读页 API 不变），再把结果块与引用列表抽成 `components/ai/QaAnswerBlock.vue` / `QaCitationList.vue`（三态语义与 `kind` 跳转各只有一份），最后加浮层 `components/assistant/AssistantDock.vue`（挂 `App.vue`、登录/注册页不出现、**不占一级导航**）。多轮走新契约 **`history: [{question, answer}]`**（上限 6 轮：Java `@Size` 与 Python `max_length` 各收一道）：提示词里 `HISTORY_PROMPT` 与 `MEMORY_PROMPT` **同口径** —— 历史是**模型自己上一轮说过的话**，当证据用等于让它拿自己的旧答案当出处。会话存 `sessionStorage`（关标签页即散，不落库：助手会话不是账号资产）。顺手修掉 `askStream(question, 5)` 这种**会被静默忽略**的旧调用形式（数字参数当 opts 用不报错，只会悄悄回退默认 topK），并在 composable 里显式兼容它 | Python `tests/test_qa_history_prompt.py` **8 条**（无历史时没有该段、历史与记忆各带说明且都在摘录之前、不得编号引用、系统提示词仍要求「只依据摘录」、默认空历史、答案长度上限、6 轮上限被拒）；Java `AiContractTest` **27**（`qa_stream_request.json` 现在带 history）+ `AiQaStreamControllerTest` **11**（新增：历史问句 trim / 答案原样透传、无历史传空表）；前端 `npm run check` 全过（**8 组**自检 + build：新增 `qa-selfcheck.mjs` **30 条**，用 vite SSR 加载真 composable —— 含「两个实例互不干扰」「流式 401/429 能被 `isAuthError`/`isRateLimited` 认出来」；它是为「构建绿、运行红」那类错误加的，见 `docs/troubleshooting.md` §6.8） |

| 阶段 | 状态 | 关键产物 | 核验方式 |
|---|---|---|---|
| **dense 检索切到向量库（Qdrant）+ 接上指纹护栏** | ✅（`.env` 已开；**A/B 验收未做**） | 用户要求「改成走向量数据库」。三件事：① **冒烟**：`scripts/qdrant_smoke.py` **11 步全过**（测试机 v1.19.0），并顺手改正了 §4.2 那句过期的「从未连过真实实例」；② **建索引**：当前语料 40 篇（post 29 + note 11）→ **92 个点 / dim 2048 / status green**，2 次嵌入调用（4.6s）；③ **接上 B-9⑦ 的第 ④ 件事**：`RetrievalPipeline.prepare()` 在 `dense_store` 非空时先 `assert_model_fingerprint()`（**每个管道实例只一次**，不必每问一句都 scroll）—— 不校验的后果是「换了 embedding 模型但索引是旧的」**不会报错**，只会返回毫无意义的结果（两边向量不在同一空间）。顺带修真机踩到的一处：`sample_model_fingerprint()` 在**集合不存在**时抛 404，而首次开向量库时索引本来就是空的 —— 那会一路冒成 500，把「还没建索引」说成「服务坏了」；现在与 `collection_info()` 同口径返回 `None`。⚠️ **两条必须知道**：① 按 `troubleshooting.md` §5.9 的既定口径，切换前应做「黄金集 30 题：内存 vs 向量库不劣化」的 A/B 对比，**这项没做**（要几十次嵌入调用，免费档每模型每日 50 次），属于用户拍板接受的取舍而非遗漏；② 新引入一种失败模式：**Qdrant 不可达时问答会整体失败**（此前应用根本不碰它）—— 本机开发是跨公网连测试机，生产 compose 里 Qdrant 是同机容器 | Python：`tests/test_pipeline.py`（新增 2 条：走向量库时指纹**只校验一次**、不一致**当场抛错**）+ `tests/test_qdrant_store.py`（新增：集合不存在 → 返回 `None` 而不抛）；ruff ✅ / mypy ✅（81 文件）/ 相关用例全过。**实测走向量库**：独立进程里 `DENSE_STORE_ENABLED=True`、`dense_store=QdrantVectorStore`，检索命中 `note 2` / `note 18`（正是问题主题），引用带 `kind`。⚠️ 运行中的 8200 需重启才生效（开关是导入期读一次） |
| **助手 A1 司职骨架（`AgentProfile` + 注册表）** | ✅ | `app/agents/profile.py`（frozen dataclass：`name`/`title`/`system_prompt`/`tool_names`/`settings`/`scene`）+ `app/agents/registry.py` 登记三个司职：`searcher`（**缺省**，带只读工具 `search_posts`，提示词与 E2 逐字相同）、`answerer`（无工具纯生成）、`verifier`（零模型调用）。三条口径：① **提示词从类常量变成司职数据** —— 原先「改一处，所有司职长成同一个」；② **工具仍是构造期装不进来**：白名单只决定装哪几个只读工具，声明了却没实现的工具名在装配期**直接报错**、不静默少装（`ToolBox` 依旧拒 `read_only=False`，两道门叠加而不是二选一）；③ **未知司职报错不猜**（422 + 可选清单，除大小写/空白外不做任何猜测）—— 回退默认会让「名字拼错」表现成「跑完了但答得不对」，而账里记的还是另一个岗位。另有一层把「注册了」与「能跑」分开：`EXECUTABLE_AGENTS` / `MODEL_FREE_AGENTS` / `SKELETON_AGENTS`（后者当前为空），不变量有单测盯。**「客户端只能收紧预算」现在是两层夹取**：Java `AiAgentController.bounded()` 取 `min(请求, 4 步 / 6 次)`，Python 再按该司职声明的 `profile.settings` 取一次 min —— 两侧都只减不增，所以「Java 放行」不等于「真的会跑那么多步」 | Python `tests/test_agents_registry.py` + `test_agent_api.py`（本轮 pytest **985 passed**，+147）；Java `AiAgentControllerTest`；契约 fixture `agent_ask_request.json` 加 `agent`（Java DTO 同步，两侧契约测试共读） |
| **助手 A2 确定性引用核验（`/agent/verify`）** | ✅ | `app/agents/verifier.py` + `POST /agent/verify`（Java 出口 `POST /ai/agent/verify`，门槛只要求登录）。**四类判定全是确定性的**：编号**越界**（`outOfRange`）、答案**一个编号都没标**（`uncited`）、引用**片段对不上原文**（`snippetNotFound`）、引用指向的**段落已不在语料**（`unknownChunk`）。**零模型调用 → 不占配额、不进调用账**：给它记一笔零成本的账，会让成本看板上的 agent 不再是「模型花了多少」；而把它拦在配额外，又会让用户以为「额度用完了连核验都不能用」。口径要点：`verdict=ok` 只表示**引用能对上原文**、**不等于「答案已核实」**（界面只说「引用核验」）；`checked`（回查过原文的条数）与 `evidenceAvailable` 必须一起回，用来区分「比过了、没问题」与「这次没得比」；`uncited` 在**拒答**（本来就没有引用）时是 `false` —— 它说的是「检索到了但没说哪句来自哪条」，与「没有依据」是两件事；问题的 `message` 是给人看的中文，前端直接显示、不按 `kind` 另编文案。比对规则**不新写**：编号解析复用 `parse_citation_marks`、文档标识复用 `citation_key`、片段比对复用 `rag/evidence.py`（与 LLM Wiki 的 quote 校验同一套空白规范化）。⚠️ **语义核验（论断有没有依据）未做**，留到下一轮 —— 它要以「片段确实存在」打底，也需要一套新的输出契约 | Python `tests/test_agents_verify.py` + `test_agent_api.py`；契约 fixture `agent_verify_request.json` / `agent_verify_result.json` + 两侧契约测试；Java `AiAgentControllerTest`；前端 `api/verification.js` 的三态文案（`checked>0` 的 ok / `checked=0` 的 ok / warn —— 合并就是把「没得比」说成「没问题」） |
| **助手 A3 拉取供应商模型清单（`/provider/models`）** | ✅ | `app/providers/model_listing.py` + `POST /provider/models`（Java 出口 `POST /ai/me/providers/models`，门槛同为登录）：拼 `{baseUrl}/models`、**只取 `id`**、按 id 去重、**限 200 条**（截断由 `truncated` 如实说明，不假装「全都在这里」）；读超时 **5s** / 连接 **3s**（拉清单是辅助信息，用对话那档 30s 不可接受）；响应体超 **4MiB 在读取时就中止**（不把巨型响应读完）。**明文 Key 只用一次**：只在这次请求的 `Authorization` 头里出现，不落库、不进日志、不进审计、**响应里连掩码都不回**。⚠️ **安全边界（本刀最要紧的一条）**：`apiKey` 留空时**只借该用户自己那一行**（`config_source.load_own_user_configs` / `runtime.saved_config_for`），**绝不回落站长全局配置** —— 地址是用户自己填的，借出全局密钥等于让**任何登录用户一次请求**把站长的明文 Key 发往他控制的地址，而日志里只会留下「有人拉了一次清单」；环境注入的整份配置同样不参与。地址按「个人配置」那一档**只允许公网**（与 `/ai/me/providers` 保存同口径，Python 侧按同一规则复校）—— 所以**站长拉 `127.0.0.1` 自建端点会被 400 拒**，要支持得另开 ADMIN 出口（不能为了站长方便给登录出口开口子）。另两条：两处都没有密钥 → **400** +「要么填 Key、要么先去存一个」（报 401 会让人去怀疑会话/内部签名）；`fake` 回它自己那**一个**标识且 `source` 是字面量（我们没向任何地址发过请求，写成地址就是替它说谎）。失败分档沿用 `provider_error_status`（400 参数/未配置、401 密钥无效、429 限流、502 不可达或不支持 `/models`），不另造一套 | Python `tests/test_model_listing.py` + `test_provider_models_api.py` + `test_provider_config.py`；Java `AiMeProviderControllerTest` + `ProviderUrlPolicyTest`；契约 fixture `provider_models_request.json` / `provider_models_result.json` + `AiContractTest`；前端 `utils/providerModels.js`（响应归一化 + `truncated` 文案 + 「接口还不存在」专用话术） |
| **助手 A4 浮层：司职切换、每轮核验与模型选择** | ✅（两条已知缺口见 §4.4） | `components/assistant/AssistantDock.vue`：① **两个司职各自独立** ——「问答」走 RAG 流式（`useQaStream`）、「深挖」走只读 Agent（`useAgent`），各一个 composable 实例、**各存一份会话**（`sessionStorage` 按司职分桶），切回去还是原来那段对话（混成一条会让「为什么这次这么慢」没有答案：两者代价差一个量级，按钮旁如实写明）；② **每轮结束自动核验一次**，结论显示在气泡下（零模型调用、不占配额，却回答「这次给的引用站不站得住」）；③ **模型选择与就地添加**（`stores/myModels.js`，只处理 `chat` 一个角色、Key 只写不读、留空 = 沿用已存密钥、`base_url` 明写「必须填 API 根」）+ **从供应商拉取候选**（**可选入口**，手打模型名一直可用；候选只在内存里、`apiKey` 不落任何本地存储；失败只写进「拉取」自己的位置，**绝不冒泡成「这次问答失败」**）。两条刻意：**拉取失败不许说成「服务坏了」** —— 接口不存在（404/405/501）时文案是「拉不到」+「手打照样能保存」（拉模型是辅助能力，说成故障会让人以为整站挂了）；**打供应商的请求带 `ownErrors`** —— 供应商的 401 意思是「密钥无效」，绝不能因此清会话、把正在填表单的用户踢回登录页（默认 `false`、老调用方一字不变，并有正对照断言防真正的会话失效被吞掉） | `scripts/assistant-dock-selfcheck.mjs`（**65 条**，SSR 真渲染 + 源码断言，并入 `npm run check`）：含「模板标识符必须有声明」（`vite build` 不校验未定义标识符，漏过一次 `STREAM_PATH`）、「下拉/候选列表必须在正常流里」等 |

## 3. 一次完整核验的命令与结果

```bash
# Python：静态检查 + 类型 + 测试
cd stellar-ink-ai
uv run ruff check .          # All checks passed!
uv run ruff format --check . # 105 files already formatted
uv run mypy app              # Success: no issues found in 54 source files
uv run pytest                # 483 passed

# 可执行的检索/评测证据（不需要 Qdrant、不需要任何 API Key）
uv run python scripts/eval_local_baseline.py   # 29 篇 / 41 子块；Recall@1 0.8333、NDCG@5 0.9485、拒答率 0.4、误拒率 0.0
uv run python scripts/compare_strategies.py    # 四组策略排序；dense 与 hybrid+rerank 相同 → 重排必须换模型
uv run python scripts/check_golden_evidence.py # 20 道有答案题证据自检：证据不足 0 道

# Java：契约、切片、公共模块
cd stellar-ink-server
mvn -pl stellar-ink-ai-client,ai-service -am test   # BUILD SUCCESS：ai-service 92 / client 40 / common-core 27
mvn -pl common-components/common-core,user-service,content-service,gateway-nacos-sentinel -am test  # BUILD SUCCESS

# 前端：纯逻辑自检 + 部署前缀核对 + 模型库写反馈自检 + 构建
cd stellar-ink-web
npm run check                  # 差异/采纳/SSE 切帧 46 条 + 部署自检 + 模型库写反馈 14 条 + vite build
```

对照基线（与阶段 C 收口时的记录一致，说明这期间没有静默退化）：
`Recall@1 0.8333 / Recall@3 0.9417 / Precision@5 0.800 / NDCG@5 0.9485 / MRR 0.975 /
拒答率 0.4 / 误拒率 0.0 / 引用准确率 1.0`。

**最近一次全量核验（2026-10-02，第 20 刀之后）**：

| 套件 | 结果 |
|---|---|
| Python | `ruff` / `ruff format --check` / `mypy`（68 个源文件）全通过；`pytest` **644 passed** |
| 后端 | `mvn test` 9 模块 BUILD SUCCESS；有测试的 6 个模块：`common-core` 59、`ai-client` 42、`user-service` 6、`content-service` 33、`gateway` 42、`ai-service` 166 —— **合计 348 passed** |
| 前端 | `npm run check`：4 个自检（含 Wiki 45 条）+ `vite build` 通过 |

⚠️ **数用例时按「本次运行」的报告数**：`target/surefire-reports/` 里可能留着**早已删除**的测试类
的旧报告（实测遇到过一次：2026-09-15 的 `WordCountDiag*Test` 报告显示 4 个失败，
但那两个类早就删了、本次运行根本没跑它们 —— 差点被当成真失败去查）。
`target/` 不入库，所以这只是本地计数陷阱，不是仓库问题。

**第一次真实模型端到端**（2026-09-25，面板配 `chat = deepseek-flash @ api.deepseek.com`，
语料仍是种子内容包、检索只开 Sparse —— 因为 `embedding` 角色还没配）：

| 路径 | 结果 |
|---|---|
| 非流式 `QaService.answer` | `doneReason=stop`、`evidenceSufficient=true`、引用 4 条、答案里带 `[1]`/`[3]` 编号、`model=deepseek-flash`、1273 tokens / 5.8s |
| 流式 `QaService.stream` | `meta`（`model=deepseek-flash`）→ `citation`×4 → `delta`×76（129 字）→ `done`；推理内容被正确跳过、未混进正文；`latencyMs=2084` |

这两次跑出来的坑都记在 §6 的第 9–10 条 —— 它们**只在真实推理模型上出现**，
Fake 与桩永远碰不到，所以「指标全绿」并不等于「接上模型没问题」。

**第二次真实模型端到端（2026-10-01）：嵌入与重排也接上了**

配置：`chat = deepseek-flash @ api.deepseek.com`、`embedding = nvidia/llama-nemotron-embed-vl-1b-v2:free`
（2048 维）、`rerank = nvidia/llama-nemotron-rerank-vl-1b-v2:free @ openrouter.ai/api/v1`。

先跑 `uv run python scripts/provider_smoke.py`（**真实往返**，不是面板那个 `tcp_only` 自检）：

| 角色 | 结论 |
|---|---|
| chat | ✓ 702ms、返回 2 字、`finish=stop`、tokens 49+51 |
| embedding | ✓ 1880ms、2 条 / **2048 维**、两段不同文本相似度 0.031（确实在编码内容） |
| rerank | ✓ 2546ms、首名 `#0`（相关那篇）、分数 `[0.7012, 0.0008, 0.0007]` |

再用**同一条检索编排**跑真实模型（`uv run python scripts/compare_strategies.py --provider panel`，
29 篇文章 / 41 个子块 / 30 道题）：

| 策略 | recall@1 | recall@3 | recall@5 | precision@5 | ndcg@5 | mrr | 拒答率 |
|---|---|---|---|---|---|---|---|
| sparse | 0.8333 | 0.9417 | 0.9583 | 0.25 | 0.9554 | 0.975 | 0.0 |
| dense | 0.8083 | 0.9333 | 0.9500 | 0.25 | 0.9474 | 0.975 | 0.0 |
| **hybrid** | **0.8583** | **0.9583** | 0.9583 | 0.25 | **0.9705** | **1.0** | 0.0 |
| hybrid+rerank | — | — | — | — | — | — | — （`errorCount=30`，见下） |
| sparse+floor | 0.8333 | 0.9417 | 0.9417 | 0.80 | 0.9485 | 0.975 | 0.4 |

三条读得出的结论：

1. **向量通路是真的**：Fake 伪向量下 dense 的 recall@1 是 0.075，真实嵌入下是 **0.8083** ——
   前者接近随机，后者与稀疏路同档。这也是「`--provider fake` 的数字不能当质量」的实证。
2. **混合检索确实带来提升**：hybrid 的 recall@1/@3、NDCG@5、MRR 全部高于单路
   （MRR 1.0 = 20 道有答案题里每次首位就命中）。这是 M4 决策门要的那个量化答案。
3. **重排那一行暂时不可用**：`errorCount=30`，30 道题全部因上游 `ProviderRateLimitError`
   （OpenRouter 免费档 429）被降级成「拒答」。**它与重排质量无关**，
   而且降级后的行看起来恰好和「开了重排就彻底失效」一模一样 —— 这正是 §6 第 15 条要防的事。

## 4. 已知缺口（**都要在接真实流量前处理**）

### 4.1 三个角色都实测可用；剩下的是**阈值标定**与**免费档吞吐**

面板是模型的**唯一来源**，代码里没有任何默认模型（`fake` 也要在面板里显式选）。装配链路：

- `app/providers/runtime.py`：全进程唯一的 `ProviderResolver`（配置指纹缓存 + 换配置即换实例），
  外加 `require_roles()` 预检（缺角色时一次说清缺哪些、去哪儿填）；
- `app/rag/corpus.py`：语料唯一缓存（`EPOCH` 版本号参与检索管道的缓存键）；
- `app/api/v1/assembly.py`：`pipeline_for()` 按「语料版本 + 检索开关 + 配置指纹」缓存检索管道
  （整库嵌入因此只发生一次），`assembly_error()` 把装配失败统一翻成 400；
- 问答 / 流式问答 / Copilot / 画像 / Agent / 评测**全部**走这条路径；
  `ProviderError` 由 `app/main.py` 的全局处理器转成 429（限流）/ 400（配置）/ 502（上游）。

仍然算缺口的部分（2026-10-01 更新）：

- **`minDenseScore` 还没标定**。Dense 通路的拒答只能靠这个余弦绝对下限（`min_score_ratio`
  永远不让结果为空），而它必须按**真实分数分布**定 —— 离线伪向量的分数在 0.03 量级，
  照那个分布定出来的门限会静默清空向量通路（§6 第 4 条就是踩过的版本）。
  ⚠️ 顺带修正一处文档口径：`scripts/calibrate_min_score.py` **扫的是 BM25 的 `min_score`
  （稀疏路），不是 `minDenseScore`** —— 它证明的是「拒答只能靠绝对下限」，不是 Dense 门限。
  Dense 门限目前没有现成工具，得先补一个「按真实分数分布扫 `minDenseScore`」的入口。
  ⏳ **工具已就位，等额度**：`scripts/calibrate_dense_score.py`（dense-only 扫真实分数分布，
  输出「仍答对 / 挡住无答案」两个比例并给推荐点；推荐逻辑有 8 条离线单测）。
  它需要真实嵌入 —— 额度用尽时**如实报错并退出**，不会产出一张全 0 的假分布
  （拿假分布定门限，正是历史上把向量通路静默清空的那个坑）。
  ⚠️ **推荐规则在 2026-10-02 修过一个会「关掉功能」的 bug**：原先的目标是
  「保住率 + 拒答率之和最大」，而**把所有题都拒掉能拿满分**（0 + 1.0）——
  在一组「无答案题分数比有答案题还高」的数据上（同主题但语料里没答案，真实里常见），
  实测它推荐了 `minDenseScore = 0.55`、保住率 **0.0**，照着配向量通路就没了。
  现在改成**两步**：先要求在可行阈值里保住 ≥90% 的本可答题，再在其中挑拒答率最高的；
  没有可行阈值时**明确说「这组数据标不出门限」**并解释常见原因，而不是给一个能把功能关掉的数字。
  所以跑的时候若看到「标不出可用的 minDenseScore」，那是**结论而不是故障**：
  先用 `uv run python scripts/check_golden_evidence.py` 看它新加的**无答案题自查**一节
  （2026-10-02 补：列出「写了参考答案文本却没标文章」的题、以及与语料措辞重合的题），
  再决定补语料还是改题。
  ⚠️ 那节给的是**线索不是判定**：措辞重合不等于有答案（同主题很常见），要人工读那篇确认。
  顺带纠一个我此前写错的数据模型口径：`answerable` 是**派生属性**
  （`expectedPostIds` 非空即为有答案），所以「无答案题带 expectedPosts」根本表达不出来 ——
  能出现的矛盾是反过来的那种（有答案文本、没标文章）。前几轮文档里那句说法是错的，已改。
  跑通后把推荐值填到 `qa.py` 的 `QA_RETRIEVAL`、`agent.py` 的 `AGENT_RETRIEVAL`
  与评测台的 `minDenseScore`。
- **免费档吞吐不够跑完标准五组** —— **已定位为「每日额度」而不是「瞬时限流」**（2026-10-01 实测）。
  两处放大因素已修：① 评测里每个 dense 策略各自嵌入整库一遍 → 已加**嵌入缓存**
  （`app/providers/embedding_cache.py`，在 `ProviderRegistry._build` 里包，整库只嵌一次）；
  ② 逐题嵌入与重排没有退避重试 → 已加**退避重试**（`app/providers/retry.py`，只重试 `retryable` 的）。
  但重跑标准五组时三个 dense 策略**仍然** 30/30 失败，直接探测上游才发现真因：
  ```
  HTTP 429  limit_source=openrouter_free_tier_daily
  X-RateLimit-Limit: 50   X-RateLimit-Remaining: 0   X-RateLimit-Reset: 次日 UTC 零点
  ```
  **免费档是「每模型每日 50 次」**，退避几秒救不了；`Retry-After` 说 600 秒也没法在一个请求里等。
  因此这一条**不是代码缺陷，是用户侧动作**：给这三条腿换付费/自建模型（上游原话是
  「Add 10 credits to unlock 1000 free model requests per day」），或等次日重置后再跑。
  已做的改进是**让它说出来**：新增 `ProviderQuotaExhaustedError`
  （`retryable=False`，不重试；对外错误码仍是 `AI_RATE_LIMITED`，前端 429 文案不变），
  消息里带「每日上限 + 重置时间 + 重试无用」。真实 smoke 输出：
  ```
  ✗ embedding  nvidia/llama-nemotron-embed-vl-1b-v2:free
      上游额度已用尽（每日上限 50 次）（将于 10-02 08:00（本地时间）重置；重试无用 —— 请改用付费/自建模型，或等重置后再跑）
  ```
  在此之前这句话是「模型服务限流，请稍后重试」——**会让人白折腾一天**。
- Qdrant 的真实往返仍未做（见 §4.2）。

⚠️ 配真实**推理**模型时一定要调大该角色的 `maxTokens`（建议 ≥2048）：
实测 `deepseek-flash` 的一次问答用了 1636 个 completion tokens，其中约 1360 个是推理内容 ——
`max_tokens` 给小了就会出现「一个字都没写出来，预算就没了」（见 §6 第 9 条）。

### 4.2 Qdrant 真实往返 —— **已冒烟通过**（2026-10-05 收口）

`app/rag/qdrant_store.py` 有 32 条协议测试（MockTransport）与 5 条内存模拟器端到端测试，
真实往返也已跑通：`uv run python scripts/qdrant_smoke.py` **11 步全过**
（建临时集合 → 写点 → 检索 → 相似度下限拒答 → 按文章删点 → 端到端索引 → 检索 → 清理），
连的是测试机 `124.221.158.32:6333`（v1.19.0）。

> ⚠️ 本节曾长期写着「Qdrant 从未连过真实实例」，与同一文件 §2 里
> 「`qdrant_smoke.py` 11 步全过」那条记录**互相矛盾** —— 那是缺口解决后没回来更新的一句话，
> 2026-10-05 按实测改正。**教训：缺口一旦被解决，要回来把「未做」那句改掉**，
> 否则它会让下一个人（这次就是我）白跑一遍、还会据此做出错误判断。

**2026-10-05 起，本机开发的 dense 检索已切到向量库**（`.env` 里 `AI_DENSE_STORE_ENABLED=true`）：
索引 = 当前语料 40 篇 / 92 个点 / dim 2048 / status green，检索实测能命中笔记。
⚠️ 按 `docs/troubleshooting.md` §5.9 的既定口径，切换前应做「黄金集 30 题：内存通路 vs 向量库通路
不劣化才切」的对比 —— **这项 A/B 验收尚未做**（要几十次嵌入调用，免费档每模型每日 50 次），
属于**用户拍板接受的取舍**，不是遗漏。

### 4.3 E2 的 Agent 前端入口 —— **已接线**（2026-10-01）

后端与网关早已就绪（`POST /ai/agent/ask`，登录即可），此前刻意没挂界面：
Agent 比一次问答慢、也更贵（可能多次调用模型），在配额与真实模型接通之前挂上去，
等于给用户一个会烧钱的按钮。**配额（E3-2）与真实模型都已到位，所以这一刀补上了。**

做法：阅读页侧栏「问星笺」面板加**模式切换**（一次问答 / 深挖），默认停在便宜的那一档；
深挖结果展示**每一步的工具名与标签**，并把三种「没给出答案」的形态分开显示：
预算用尽（`doneReason=length`，**不是失败**，常常还带着引用）、用户停止、
请求失败（含 429 配额）。前端自检 `scripts/agent-selfcheck.mjs`（22 条）把这三条钉住，
已并入 `npm run check`。

⚠️ 顺带修正两处**会骗人**的地方：
① 界面原本想写「深一点（6 步）」，但 ai-service 用 `min(请求值, 4)` 夹住预算 ——
传 6 只会跑 4，所以改成「快一点（3 步）」与「标准（不带该字段，服务端决定）」；
② `api/client.js` 的 `request()` 原本不接受调用方的 `signal`，「停止」只是本地不再等，
服务端照样跑完多步检索（白烧钱）—— 现在真的会中止请求。

### 4.4 另外两件小事

- **前端只对 `utils/` 里的纯逻辑与 store 的写反馈做了自检**，`AiLabView` / `CopilotPanel` 的
  DOM 交互仍没有自动化测试（没有引入测试运行器，见 `AGENTS.md` §3）。
  store 那层由 `scripts/ai-store-selfcheck.mjs` 用 Vite 的 `ssrLoadModule` 加载真实 store 来跑
  （写成功 + 刷新 503 的时序就是这么测的）；面板本身的验证仍靠 `npm run check` 的构建 + 人工。
- ~~**配额与 `retrievalAudit` 未实现**~~ → **配额已在 E3-2 落地**（用户·角色·并发三维，触顶 429）；
  Nginx 的 `ai` 档（30r/m）仍在，那是边缘限流、与配额是两层。
  `retrievalAudit`（把每次检索的候选与分数落库）仍未做 —— 目前靠 E3-4 的按 traceId 回放看链路。
- **`AiHealthControllerTest.doesNotLeakConfiguration` 的偶发失败：已定位「假失败的来源」并消除**。
  症状：同样命令连跑两次结果不同（隔离跑 7/7、`mvn test` 全量复跑通过，但组合跑里失败过一次，
  报「泄露了敏感内容：8200」）。排查结论：控制器**从不把探针原因写进响应**（只写日志，
  响应里是固定文案），所以唯一可能含纯数字串的是 **`traceId`** —— 它是 `UUID.randomUUID()`
  去掉横线的 32 位十六进制，而 `8200` 是纯数字：**实测 20 万次命中 93 次 ≈ 每次 1/2151**。
  ⚠️ 这个概率**不足以证明**它就是元凶（本次会话该用例只跑了数十次，撞上的先验约 1%~2%）——
  所以处置不是「断言没错」，而是**把扫描范围收敛到该扫的地方**：只扫**内容字段**
  （配置只可能从那里泄露），随机 id 不再参与敏感串比对；同时保留一条
  「traceId 仍在响应里」的断言，免得有人删字段来「修测试」。
  若它再次出现，说明上述判断错了，按「内容字段里真的出现了 8200」重新查。
- **助手浮层的两条已知缺口（2026-10-05，司职/核验/模型选择那一刀）**：
  ① **`docs/ai/status.md` 之外的进度流水按需补** —— 本轮只把核验记录落在本文，
  方案与验收细节散在 `docs/ai/assistant-plan.md` 一类专题文档里，其余按需再补；
  ② **浮层模板的布局问题没有自动化覆盖**：自检跑的是 **SSR，没有布局引擎**，
  所以「下拉被面板 `overflow:hidden` 裁掉、用户以为点不了」那次事故**只靠一条写法约束断言**守着
  （不许回到 `position:absolute` 的回归护栏 + 「候选列表在正常流里」），真实视觉回归仍要人工看一眼 ——
  这也是 SSR 自检的边界：它能证明 DOM 与数据对，**不能证明它长得对**。

## 4.5 已拍板的三件事（2026-10-01，用户决定；不要再当成待办去问）

1. **E4 先做 LLM Wiki，GraphRAG 排后面**。按 `implementation-roadmap.md` §14 的顺序切，
   第一刀是**带证据的主张抽取**（E4-1）：每条主张绑定 `post_id` + 段落位置 + 内容版本 + 原文片段，
   并且**校验引用真的出现在那篇文章里**（不通过就丢弃，不写进页面）——
   验收口径就是原文那句「Wiki 的事实性文本必须能回到证据」。
2. **暂不部署 OpenTelemetry / Langfuse**：E3-4 的**进程内回放就是接受形态**。
   跨副本查不到时会如实返回 `found=false`，这是**已知且被接受的限制**，不是缺陷；
   长期留存同理。将来若要跨副本，再按 §4.1/README 的路线补集中存储。
3. **两件环境动作由用户处理**：给 embedding / rerank 换付费或自建模型（解除免费档每日 50 次），
   以及开 Qdrant 隧道（或给出可达地址）。做完之后：
   跑 `uv run python scripts/calibrate_dense_score.py` 拿 `minDenseScore` 标定值、
   重跑标准五组补全 dense 三行、跑 `uv run python scripts/qdrant_smoke.py` 收口 B 阶段。
   ⚠️ 换模型后**必须重新标定**（余弦分布随模型变），且嵌入缓存按模型指纹隔离、不会串味。

## 5. 需要使用者做的事（代码之外）

1. 在服务端 `.env` 里设置 `AI_SECRET_MASTER_KEY`（32 字节 base64）与 `AI_INTERNAL_SECRET`（≥32 字符）——
   两者都**没有默认值**，缺失时相关能力直接拒绝，不会静默降级。
2. 执行 `mysql -u root -p stellar_ink < deploy/sql/10_ai-schema.sql`（`ai_*` 表）。
   已有升级脚本按需各执行一次：`11_ai_model_library.sql`、`12_ai_call_log.sql`、
   `13_ai_wiki.sql`（E4 的知识条目表；**读者侧的「知识条目」区块依赖它**，
   没建表时读取接口会报表不存在 —— 而前端会静默降级成「本文没有知识条目」，
   现象上就是「功能看起来没上线」，所以别漏这步）。
3. 在 `/ai-lab` 面板里填 API Key（只写不读，列表只回掩码）并跑一次连通性自检。
   ⚠️ 那个自检是 `scope: tcp_only`：**只证明端点可达，不验证模型名与密钥**（已实测过这个差别）。
   真正的验证是 `uv run python scripts/provider_smoke.py`：它读的就是应用读的那份配置，
   按角色各打一次真实调用，把「配了」与「能用」分开 —— 面板那个自检**对配置错误一律沉默**。
   配推理模型时记得把该角色的 `maxTokens` 调到 2048 以上。
4. `embedding`（向量维度照服务方文档填，实测该模型是 **2048**）与 `rerank` 角色已配好并实测通过；
   换模型后要重跑 §4.1 里的两件事（真实评测 + 门限标定）。
5. 跑一次 Qdrant 真实冒烟（§4.2 的命令）。
6. **本轮同时改了 Python 与 ai-service，两边都必须重启才生效**（Python :8200 的 `app/agents`、
   `app/api/v1`、`app/providers`；ai-service :8107 新增的两个出口）。
   ⚠️ **没重启时点助手里的「拉取模型」会显示「拉不到」——那是预期，不是 bug**：
   前端对「这个接口还不存在」（404/405/501）有专门处理，说的是「拉不到模型列表：后端还没有这个接口」，
   并附一句「手动填模型名照样能保存」；同理 `/ai/agent/verify` 会 404，而引用核验是**辅助信息**，
   失败就不显示（`safeVerify` 吞掉异常），不会损伤问答主流程。

## 6. 这 20 轮里最值得记住的几个坑

写在这里是因为它们**都不会报错**，只会让人对着正确的数据得出错误的结论：

1. **伪造 `http.disconnect` 等于自己掐断自己的流**：`InternalAuthMiddleware` 读 body 后
   一律返回断开，非流式接口完全正常、SSE 直接 500。（`development-workflow.md` §9）
2. **`@WebMvcTest` 的切片会被启动类的显式 `@ComponentScan` 破坏**：新增控制器可能让**别人的**切片起不来。
3. **`min_score_ratio` 永远不让结果为空**：拿它当拒答开关，拒答率恒为 0，
   而指标、日志、响应全都正常。拒答只能靠绝对下限。
4. **给离线链路设「看起来合理」的阈值比不设更危险**：`minDenseScore=0.2` 让向量通路静默失效，
   混合检索退化成纯 BM25。
5. **接口前缀漏配代理会静默失败**：`/ai` 既没进 vite proxy 也没进 nginx location，
   dev 与生产都表现为「请求成功但页面空白」。现在由 `scripts/deploy-selfcheck.mjs` 盯着。
6. **测试断言的取样范围必须与实现一致**：画像只取前 20 篇，我按 25 篇数关联词，
   于是排名差一位、看起来像算法不稳定。
7. **脚本最后一行打不出的字符会让退出码变 1**：`⚠️` 在 GBK 控制台上抛
   `UnicodeEncodeError`，指标全对但脚本「失败」。现在所有脚本入口都调 `use_utf8_console()`，
   并由 `tests/test_scripts.py` 盯着。
8. **预算账目要在拿到东西之后再记**：Agent 先在「拿到可用观察前」加工具调用次数，
   于是账单与日志各说各的。
9. **推理模型的「思考」也占 `completion_tokens`**：`deepseek-flash` 会先产出一大段
   `reasoning_content`（实测一次问答 1636 个 completion tokens 里约 1360 个是思考），
   `max_tokens` 给小了就是「content 为空 + `finish_reason=length`」。
   原来的 `ChatResponse.refused` 只判「文本为空」，于是把它报成**模型拒答** ——
   用户会去查安全过滤与提示词，而真正要做的是把 `maxTokens` 调大。
   现在截断单独用 `DoneReason.LENGTH` 表达，空输出只有「正常结束」或 `content_filter` 才算拒答。
10. **流式的 `usage.latencyMs` 是「单块」耗时（恒为 0），不是一次回答的耗时**：
    照抄它会让跑了 2 秒的回答在响应里显示成 `0ms`，而前端与运维正是用这个数字判断链路快慢。
    现在由编排层自己计时（与上游上报值取较大者）。
    同一条链路上还有个小坑：展示用的模型名原来只 `getattr(chat, "model")`，
    而真实 Provider 的模型名在 `config.model` 上 —— 于是 SSE 的 `meta.model` 一直是 `unknown`，
    链路完全正常却看起来像「没接上模型」。三处症状的共同点是**都没报错**，
    只有真的打一次真实模型才看得见（`tests/test_chat_response_edges.py` 现在盯着它们）。
11. **Python 的错误体没有解码器 = 可操作的提示全被丢掉**（实测）：Python 刻意把「没配模型」
    做成可读的 400，而 `PythonAiClient` 没有 `ErrorDecoder`，非 2xx 一律退化成裸的
    `FeignException`，用户看到的是 `code=500「系统繁忙，请稍后重试」`。
    于是「去面板配一个角色」变成了「服务坏了」。现在由 `PythonErrorDecoder` 翻成业务异常，
    并刻意**不**把上游 401/403 映射成「未授权」—— 那会让前端把用户清出登录态。
12. **配置键写错不会报错，只会静默退回默认值**：所有 yml 配的是
    `stellar.ink.ai.python-base-url`，而 Feign 与 SSE 客户端读的是 `ai.python.base-url`
    （哪里都没定义），于是地址永远走硬编码的 `127.0.0.1:8200`。
    本地碰巧一致所以看不出来，Docker 里就是「探活说可用、功能全挂」。
    守住它的是 `PythonAiClientErrorContractTest`：只改那一个键 + 哨兵消息，
    任何一种静默退回都会让它红。
13. **`fallbackFactory` 在当前装配下其实不生效**：ai-service 没有 circuit breaker 依赖，
    Spring Cloud OpenFeign 因此走「忽略降级」的那条路径 —— `PythonAiClientFallbackFactory`
    写得很完整却从未被调用（异常直接穿到调用方，实测确认）。
    也就是说「Python 挂了」现在表现为 `code=500` 而不是「服务不可用」。
    **已决定并执行：删掉那个从不生效的降级工厂**（`AGENTS.md` §5 AI 口径已同步）——
    留着一个「看起来在保护你」的类，比没有它更危险。要么将来补上 circuit breaker 依赖
    并显式配置超时，要么维持现状（异常穿透 + 全局处理器给 `code=500`），二者不要再混。
14. **「保存按钮是假的」不是后端坏了，是写成功之后的刷新失败被当成了写失败**（用户报的）：
    模型库面板点「保存到模型库」看起来毫无反应。后端探针（POST/GET/DELETE 全 200）排除了接口问题；
    真正的原因是 `stores/ai.js` 的 `saveModel` 在 POST 成功后又 `await this.loadModels()` +
    `fetchProviders()`，而这两条 GET 都过网关（撤销校验 fail-closed，抖动时 503）——
    一次刷新失败就把**已经落库**的保存显示成失败：没有成功提示、表单不关、列表里一条都没有，
    再点一次还会看到「已经有同名模型」。
    现在固定两条：写成功**先就地更新本地状态**，刷新走 best-effort（`refreshAfterWrite()` 不抛），
    并由 `scripts/ai-store-selfcheck.mjs`（在 `npm run check` 里）盯着「刷新全 503 时写操作仍算成功」。
    同一轮还补了另一个同症状的问题：`.btn` 没有 `:disabled` 样式，
    **禁用的保存按钮和可点的一模一样**，点下去什么都不发生 —— 现在禁用态有可见差异，
    且表单不再用 `disabled` 挡校验，而是点得动并就地提示「还差：展示名、接口地址…」。

### 2026-10-01 追加的四条（真实模型 / 真实库上踩的）

15. **路径拼接错误会伪装成「模型名不存在」**：面板把 `rerank` 的 `base_url` 填成完整端点
    `https://openrouter.ai/api/v1/rerank`，而 `openai_compatible._post("/rerank", ...)` 还会再拼一次
    → 实际请求 `/api/v1/rerank/rerank` → **404 text/plain**，报出来的话是
    「模型服务拒绝了请求（HTTP 404）…常见原因：模型名不存在或不支持该参数」。
    用户会去改模型名，而真因是多写了一截路径。**base_url 是 API 根**（`https://openrouter.ai/api/v1`），
    路径由代码拼 —— 面板的自检是 `tcp_only`，对这类错误**一声不响**（它当时还显示 ok/unknown）。
    现在由 `scripts/provider_smoke.py` 打真实调用来兜住这一类。
16. **上游故障被降级成「拒答」后，会变成一条看起来像质量结论的假数据**（本轮最有价值的一条）：
    免费档嵌入模型 429，`eval_runner._retrieve_safely` 把每道题的异常静默吞成「拒答」——
    于是对比表上 `hybrid+rerank` 是 `recall 0 / 拒答率 1.0`，读起来就是「开了重排就彻底失效」，
    而当时那个 `except` **连一行日志都没打**，脚本、面板、日志三处都没有线索。
    现在：`CaseResult.error` 记原因 + `logger.warning` 逐题打 + 指标里出 `errorCount` +
    响应 `notes` 首条警示 + 命令行单独喊一行。**判据：`errorCount` 非 0 时那一行指标不可用。**
17. **「配置读不到」会被伪装成「服务坏了」**：测试机的 `root@'%'` 只有 `USAGE` 权限，直连报
    `1044 Access denied ... to database 'stellar_ink'`；这条异常原样冒到端点，而
    `ASSEMBLY_ERRORS` 不认它 → `code=500「系统繁忙，请稍后重试」`。
    真因是一句授权问题，界面却说服务故障。现在 `_fetch_rows` 把连接/查询异常统一翻成
    `ProviderConfigError`（带「读的是哪个库」与「该核对哪几个变量」，**不回显密码**）。
18. **测试的加载器少一步，报错会指到脚本作者头上**：`tests/test_scripts.py` 的
    `load_module_from_path` 原先 `exec_module` 前不注册 `sys.modules`，于是任何脚本只要用了
    `@dataclass(slots=True)`（生成新类时要读 `sys.modules[cls.__module__].__dict__`）就会
    `AttributeError: 'NoneType' object has no attribute '__dict__'`，栈底落在 `dataclasses.py` ——
    看起来像脚本自己写坏了。真实 `import` 一定有注册这一步，现已补上并有回归测试。

### 2026-10-01 下午：做 E3-1（调用账）时踩的两条

19. **`Set-Content -Encoding UTF8` 会写 BOM，javac 直接报「非法字符 '\ufeff'」**：
    用脚本批量给 7 个切片测试插入 `@MockBean` 时踩到 —— AGENTS 里记过同款坑（Python 生成的模板带 BOM），
    这次换成了 PowerShell 的 `Set-Content`。正确写法是
    `[System.IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($false)))`；
    已入库文件若被写坏，用 `ReadAllText` + 无 BOM 的 `WriteAllText` 可原地修回。
20. **切片测试的地雷是「新增一个 @Service」而不是「新增一个控制器」**：启动类的显式 `@ComponentScan`
    让 `@WebMvcTest` 会把**所有**组件装配一遍，于是 `AiUsageServiceImpl` 一落地，
    **7 个既有切片测试同时起不来**（`No qualifying bean of type AiCallLogMapper`）。
    这次的处理办法是给它们各加一个 `@MockBean(answer = Answers.CALLS_REAL_METHODS) AiUsageService` ——
    把「包住一次调用」做成接口的 **default 方法**之后，替身不必 stub 就能透传调用。
    新增依赖 Mapper 的服务时，请一次把这批测试改完，别等 CI 红。
