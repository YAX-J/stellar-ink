# 星笺助手（全站笔记 / 随笔问答）方案

> 状态：**P1 / P2 / S1 / S2 / S3 已交付**（2026-10-05）。拍板结论见 §5，交付记录与实测见 §9。
> 只剩 S4（范围限定）按拍板放到二期。
> 前置阅读：[`README.md`](README.md) §6.2 星海问答 · [`status.md`](status.md) · [`../status.md`](../status.md) §4。
> 术语（本文统一）：**随笔 = 站内文章 `post`**（深读页 `/read/:id`）；**笔记 = 技术笔记 `note`**（`/note/:id`）。

## 1. 结论先行

「就全站内容提问、答案必须带引用、没依据就直说」这条链路**已经建成并跑通过真实模型**
（`/ai/qa` 非流式 + `/ai/qa/stream` SSE，调用账、检索审计、拒答口径、评测台都在）。
所以这件事**不是从零做一个聊天机器人**，而是补五处缺口 —— 其中**两处必须先修，否则助手一上线就会
把不该答的内容答出来**：

| # | 缺口 | 性质 |
|---|---|---|
| ① | **笔记根本不在检索语料里**（数据已同步好，Python 只取 `kind='post'`） | 功能缺口（本需求的主体） |
| ② | **语料是进程级永久缓存**：投影表每 5 分钟更新，Python 永不重读 | ⚠️ **前置**：现在「下架/删除的内容继续被回答」，接了笔记就升级成「**私有笔记被回答**」 |
| ③ | **没有「助手」形态**：唯一入口是深读页正文下面那块面板 | 形态缺口 |
| ④ | **没有多轮**：`conversationId` 字段在但未持久化、Java 层也不传 | 形态缺口 |
| ⑤ | **流式问答完全绕过配额与并发闸门**（只事后记账），而前端默认走流式 | ⚠️ **前置**：助手若主推流式，「配额」是假的 |

**推荐路线：先修 ②⑤（前置）→ 打通 ①② 并验收 → 再做 ③④ 的助手形态。**
**不新建问答接口** —— 助手复用 `/ai/qa` 与 `/ai/qa/stream`，SSE 帧格式、取消传播、拒答口径、
调用账全部免费复用。

## 2. 现状盘点（可直接复用的资产）

| 层 | 已有什么 | 位置 |
|---|---|---|
| 可见性规则（唯一一份） | 文章 `status=1`；笔记 `status=1` **且** `visibility='PUBLIC'`；草稿与私有笔记在清单里不出现、按 id 取正文也 404 | content-service `InternalCorpusServiceImpl`（`/internal/corpus`；网关无 `/internal/**` 路由） |
| 语料投影 | `ai_content_snapshot`（`UNIQUE(kind, content_id)`，含 content/tags/author_id/doc_hash/word_count），**全量对账**式同步，5 分钟一轮 + 手动触发 | ai-service `corpus/`（`CorpusSyncServiceImpl`、`CorpusSyncJob`） |
| 检索 | 切块（父块+子块）、BM25、Dense、RRF、Rerank、三类门限、**空即拒答** | Python `app/rag/{chunking,retrieval,pipeline,qdrant_store}.py` |
| 问答 | `/ai/qa`、`/ai/qa/stream`：**全站检索，请求体没有 postId**；`evidenceSufficient` / `doneReason=refused` | `AiQaController` / `AiQaStreamController`；Python `app/rag/qa.py` |
| 只读 Agent | `/ai/agent/ask`（预算服务端夹到 `min(请求值, 4 步/6 次)`、引用必须被观察到）；前端「深挖」页签已接 | Python `app/rag/agent*.py` |
| 前端问答能力 | 流式逐帧渲染、引用可点、停止、离线自测、中断、拒答**三态分明** | `stores/qa.js`、`utils/sse.js`、`ReadView.vue:394-521` |
| 配额 / 审计 / 回放 | 三维配额（用户·角色·并发）、`ai_call_log` 场景埋点、按 traceId 回放 | `AiUsageService.around`、`AiRetrievalAuditService`、`AiTraceController` |
| 评测 | 黄金集 30 题 + 标准五组策略 + `/ai-lab` 面板 | Python `app/rag/eval_*` |
| 网关 | `/ai/**` 走 `lb://ai-service`；**未列举路径 = 登录即可**，助手路径不用改网关 | gateway `SaTokenConfigure`（只有 `/ai/admin/**` 与 `/ai/writing/**` 卡角色） |

## 3. 缺口细节

### 3.1 笔记不在语料里 —— 且不能只改一行 SQL（缺口 ①）

`stellar-ink-ai/app/rag/content_source.py:13-16` 已写明原因：

> 文章与笔记的 id **各自自增**（文章 3 与笔记 3 是两篇），而整条引用链路
> （本服务的 `postId`、向量库 payload、Java 的引用 DTO、前端 `/posts/{id}` 跳转）目前只认一个 `postId`。

Java 侧**已经就绪、不用改**：`CorpusKind` 含 `POST/NOTE`、投影表以 `kind + content_id` 为唯一键、
NOTE 已有同步用例、`/internal/corpus` 的可见性已把「已发布且 PUBLIC 的笔记」算进来。
Python 侧要改的是四处**同源**的位置（漏一处就是「管道能用、引用错位」）：

- `content_source.py:55-59`：`WHERE kind = 'post'` → `kind IN ('post','note')`；`SnapshotDoc` 补 `kind`。
- 协议：`PostLike`（`pipeline.py:38-58`）、`IndexedChunk`（`:61-77`）、`VectorHitLike`（`:102-112`）
  三处加 `kind`；`Chunk.to_payload()`（`chunking.py:63-77`）与
  `chunk_id`（`chunking.py:204`，现为 `p{post_id}:v{version}:...`）一起改 —— **两种内容会撞 id**。
- **删除与对账改成按 `(kind, id)`**：`QdrantVectorStore.delete_by_post_ids`（`qdrant_store.py:448-452`）
  现在只按 `postId` 过滤；`index_pipeline.py:104-132`、`index_reconcile.py`、`api/v1/index.py`
  以及 **Java 的 `IndexRebuildRequestDTO`（只有 `postId`）** 同步改。
  不修这条的后果：**重建笔记的索引会顺手删掉同号文章**，且日志无异常。
- ⚠️ 顺序是死的：**先给块补 `kind`（写入侧）→ 再谈按 `kind` 过滤**。`api/v1/assembly.py:97-101` 记过：
  对**不存在**的 payload 字段做过滤，Qdrant 会**静默命中零条**（表现为「检索彻底失灵」）。

### 3.2 语料是进程级永久缓存（缺口 ②，**前置**）

`app/rag/corpus.py` 的三个 `lru_cache(maxsize=1)`（`:44`、`:73`、`:87`）是**进程级永久缓存**，
唯一的换代入口是 `reset_corpus()`（`:100-108`）—— 而它的唯一调用者是 `assembly.reset_assembly()`，
后者只被 `use_provider_configs()`（`assembly.py:78`，测试/离线接缝）使用。**生产端点没有任何调用者。**

后果（今天就已经存在，接笔记后升级）：

- 新发布的文章**进不了问答**，直到 Python 进程重启（Java 每 5 分钟同步投影表，但不会通知 Python）；
- 已下架/已删除的内容**继续被回答**；
- **一旦把笔记接进语料**：「笔记转为私有」在上游表现为「投影表删行」（`CorpusSyncServiceImpl:126-130`，
  隐私优先、不做防误删阈值），而 Python 缓存里那份正文还在 ⇒ **私有笔记会被继续答出来**，
  直到进程重启。这直接触碰红线「私密内容默认不出内网、不进公共索引」。

所以接笔记**必须**配套一个语料刷新机制（二选一，见 §6 的 S2）：
① 缓存加 TTL（例如 60s）并在超时后重读投影表；② 新增一个内网/ADMIN 端点触发 `reset_corpus()`，
由 ai-service 同步成功后调用。推荐 ①（不依赖调用方记得触发），并把 TTL 做成配置项。

### 3.3 「助手」形态：现在只有深读页里的一块面板（缺口 ③）

- 面板放在深读页正文**下面**，而它问的**已经是全站**（kicker 原文「ASK · 就全站文章提问」，
  `ReadView.vue:398`），用户以为在问当前这篇。
- `stores/qa.js` 是**单一全局 store**：`answer` 只有一份。浮层要常驻所有页面，而深读页也有面板 ——
  **两者同时挂会互相覆盖 state**（流式的 delta 会串到另一个界面）。所以 S3 必须先把流式逻辑
  抽成 composable（`src/composables/` 目前是**空目录**），并抽一个引用列表组件
  （引用渲染与 `openCitation` 现在内联在 `ReadView.vue:512-519`）。
- 一级导航固定 6 项（此刻/星图/笔记/流星/回声/星链），**助手不占一级位**。
  现成的非一级入口只有两处：顶栏右侧 actions（搜索/执笔，`TopNav.vue:142-156`）与头像菜单（`:175-194`）。

### 3.4 多轮：字段有、实现没有（缺口 ④）

`QaStreamRequest.conversation_id` 注释即「M0 不持久化」（`schemas/qa.py:52-56`），Python 侧没有会话存储；
`AiQaController.java:68-72` 构造内部请求时**不带** `conversationId`；前端 body 只有 `{question, topK}`
（`stores/qa.js:97`）。在补上之前，任何追问都会被当成一个全新问题。

### 3.5 流式路径绕过配额（缺口 ⑤，**前置**）

`AiQaStreamController.java:105,110,115` 直接调 `usageService.recordSuccess/recordFailure`，
**从不经过 `AiUsageService.around` / `acquireQuota`** ⇒ 流式问答不检查「用户每日调用数、每日 token、
角色每日调用数、并发」四道闸门，只事后记账。而前端**默认走流式**（`stores/qa.js:17,71`）。
所以「配额已落地」目前只对非流式成立。助手若主推流式，必须先补这一处。

顺带一条**会继承到助手的既有 bug**：`stores/qa.js:116-120` 用 `isAuthError()` / `isRateLimited()`
判断流式错误，而这两个函数要求 `error instanceof ApiError`（`api/client.js:46-53`），
但流式分支抛的是普通 `Error`（`stores/qa.js:100-102`）⇒ **流式的 401 不会触发清会话、429 不会弹提示**。
助手要复用这段逻辑，就得顺手修（Agent 走 `request()`，那条是正常的）。

## 4. 连带影响：还有四处只认 `post_id`

打通 `kind` 不是「改引用」一件事 —— 下面这些地方都存/收一个裸 `post_id`，笔记进来后会与文章**互指**：

| 位置 | 现状 | 建议 |
|---|---|---|
| Wiki 主张 `ai_wiki_claim.post_id`、实体提及 `ai_wiki_entity_mention.post_id` | 纯 id，且由**语料**抽取（`wiki.py`） | 短期：Wiki 构建**继续只吃 `post`**；长期：随 `kind` 一起扩 |
| 作者记忆证据 `ai_memory_evidence.post_id` | 纯 id，可为空 | 同上（记忆来自对话，影响较小） |
| 评测黄金集 `expectedPostIds` | 纯 id（`eval_runner.py:51,81`） | 加题时按 `(kind, id)` 标注；不加则评测数字仍只覆盖文章 |
| 阅读页 Wiki 读取 `/ai/wiki/posts/{postId}/claims` | 纯 id | 若 Wiki 只吃 `post`，这一处**不用改** |

**这一节是「先做 S1+S2、别一次铺开」的理由**：把 `kind` 铺到 Wiki/记忆/评测是另一条独立的刀，
混在一起会让「助手答错了」与「知识库锚点错了」难以区分。

## 5. 已拍板的四件事

| # | 问题 | 结论 |
|---|---|---|
| 1 | 助手形态 | **右下角浮层气泡**（常驻入口，不占一级导航）；深读页面板保留 |
| 2 | 内容范围 | **只答公开内容**：已发布文章 + 已发布且 `PUBLIC` 的笔记（沿用 content-service 的唯一规则，零泄漏风险） |
| 3 | 多轮 | **这一轮就做**：前端会话 + 契约加 `history`；⚠️ 历史不是证据（与 M9 记忆同口径：只调语气与取舍，不得当事实、不得据它编号引用） |
| 4 | 范围限定（S4） | **二期**（先做「能问到笔记」，范围收窄是锦上添花） |

## 6. 分刀（每刀一个主题、可独立验收）

### 前置刀 P1 · 流式配额收口（缺口 ⑤）

`AiQaStreamController` 改为经 `AiUsageService.around(…)` 或在开流前 `acquireQuota`、
在 `finally`（含取消与异常）`releaseQuota`。验收：流式问答触顶返回 429 且**不消耗模型**；
并发闸门对流式生效；取消时凭据被释放（否则并发额度会泄漏）。

### 前置刀 P2 · 语料刷新（缺口 ②）

`corpus.py` 的语料缓存加 TTL（配置项，默认 60s；`EPOCH` 随之递增，保证管道缓存换代），
或提供 `reset_corpus()` 的触发端点由 ai-service 同步后调用。验收：
① 新发布的文章在 TTL 内可被问答；② **笔记转为私有后，TTL 内不再被回答**（这条是隐私验收，
必须有一条测试盯着它）；③ 换代后引用不指向错位段落。

### S1 · 语料与索引侧接笔记（不对外）

即 §3.1 的四处同源改动 + 单测。验收：`(kind, id)` 双键贯穿取数→切块→payload→删除→对账；
文章 3 与笔记 3 同时存在时**重建其一不动另一**；私有笔记（投影表里本就没有）不出现。

### S2 · 契约打通 `kind`（跨语言，改一处要改四处）

按仓库规矩同时改：Python 模型 → `tests/fixtures/*.json` → Java DTO → `docs/api/README.md`。

- 字段设计（**推荐 A**）：
  - **A（推荐）**：`Citation` 加 `kind`（`post` / `note`，**缺省 `post`**），**`postId` 不改名**，
    文档明确「`postId` = 该 `kind` 下的内容 id」。理由：`postId` 已渗进向量库 payload、Wiki、
    检索审计、评测标注与前端，改名等于全库重构；缺省值让旧数据与旧 fixture 不炸。
  - B：改名 `id` + `kind`（语义最干净，改动面最大，需要一次性迁移所有下游）。
- Java `CitationDTO` 加 `kind`（枚举序列化必须**小写**，与 Python 契约一致，靠 `@JsonValue`）；
  SSE 路径**不用改**（`QaSseFrame.raw()` 原样转发事件体）。
- 前端：`openCitation()` 按 `kind` 跳 `read` / `note` 路由；引用卡片带「文章 / 笔记」小标识。
- 验收：构造 `{kind:'note', postId:11}` 的引用，点击落到 `/note/11` 且能看到原段。

### S3 · 右下角助手浮层 + 多轮（前端为主）

- 挂点：`App.vue:32-43`（与 `ToastCenter` 并列；`.app-root{display:block}` 不产生 fixed 包含块）。
- 先抽 `composables/useQaStream.js`（否则浮层与深读页争 `stores/qa.js` 的单一 state）+
  `QaCitationList.vue`（引用渲染与 `openCitation` 现在内联在 `ReadView.vue`）。
- 浮层内容：对话流、引用、停止、拒答/中断/离线三态文案、清空会话；
  `meta` 到即显示模型标识，`citation` 到即渲染引用，`delta` 边到边追加，缺 `done` 提示「回答中断了」。
- 多轮：前端会话（`sessionStorage`）+ 契约加 `history: [{question, answer}]`（有上限），
  Python 拼进提示词调整语境。⚠️ 历史**不是证据**；且它与 M9 记忆一样**不进引用编号**。
- 未登录：浮层入口可见、点开提示「提问需要登录」+ 跳登录（复用现有文案与口径）。
- 验收：见 §8。

### S4 ·（二期）范围限定

契约加 `scope`（`{kind?, tag?, authorId?, contentIds?}`），Python 检索侧按 scope 过滤；
依赖 S1 的 payload 字段（先写后过滤）。用于「只问这篇」「只问某标签/某作者」。

## 7. 风险与对照（都是本仓库踩过的坑）

1. **id 撞号 ⇒ 误删**（§3.1）：不修删除侧就接笔记 = 数据会丢，且日志里看不出来。
2. **缺失字段过滤 ⇒ 零命中**（`api/v1/assembly.py:97-101`）：先写 payload，后过滤。
3. **进程级缓存 ⇒ 隐私事故**（§3.2）：接笔记的前提是刷新机制，P2 不可省。
4. **免费档额度**：embedding/rerank 每模型每日 50 次（次日 UTC 零点重置）。
   语料从「29 篇文章」变成「29 + 11 公开笔记」需要重新嵌入 —— 一次全量重建可能吃掉大半额度：
   先 `POST /ai/admin/corpus/sync`，再跑一次对账/增量，别一上来就全量重建。
5. **对账任务当前是关着的**：`IndexReconcileJob` 默认关闭且 yml 里没有对应配置键
   ⇒ 索引与语料一致性目前只能手动 `POST /ai/admin/index/reconcile`。接了笔记后更要手动盯。
6. **管道持有 httpx 客户端**：缓存住管道就等于缓存住客户端，换代时要能丢掉它。
7. **引用必须被观察到**：Agent 与 Wiki 已有「引用要能在原文里找到」的校验，问答引用来自检索结果本身 ——
   助手**不要**在 Java/前端层「补引用」。
8. **「没给出答案」有三种形态**：预算用尽（不是失败）、用户停止、真正失败；
   浮层要沿用三态文案，别把前两种显示成错误。另：问答链路**不会**产生 `doneReason=cancelled/error`
   （取消时干脆不发 `done`），前端只能靠「没收到 done」判断中断。
9. **不得承诺服务端不会兑现的数字**：`min(请求值, 默认)` 的口径照旧 —— 只提供**收紧**档，或不带该字段。
10. **三处过期注释**（`content_source.py:20-29,82-85`、`AiContentSnapshot.java:17-18`）与代码行为相反，
    读它们会得出错误结论；S1 顺手改掉，避免下一个人踩。

## 8. 端到端验收清单（P1–P2 + S1–S3 完成后手测）

> **现状（2026-10-05）**：三条已由**自动化测试**覆盖（勾选）；其余是**浏览器端到端手测**，
> 需要先重启 8200（Python 侧改动不会自己生效）。手测步骤：重启后开一个页面，
> 看右下角「问星笺」气泡，按下面的问题各问一遍。

- [ ] 问一个**只写在笔记里**的技术细节 → 引用指向 `/note/{id}`，点开能看到那段原文。
- [ ] 问一个**只写在文章里**的问题 → 引用指向 `/read/{id}`（不能因为接了笔记就都变了）。
- [ ] 问站里**根本没有**的东西 → 明确「没有找到依据」（`evidenceSufficient=false`），不留白。
- [x] **把一条公开笔记改成私有** → 助手在缓存 TTL 内不再引用它（隐私验收，必须有自动化测试）。
      → `tests/test_corpus_ttl.py`（上游删行后语料与切块里都没有它）
- [ ] 新发布一篇内容 → TTL 内即可被问到（不必重启 Python）。
- [ ] 未登录点开浮层 → 「提问需要登录」+ 跳登录（不是 500、不是空白）。
- [ ] 流式配额触顶 → 429 独立文案（不是「请求失败」），且没有真的调模型。
- [ ] 生成中点「停止」→「已停止等待」，服务端确实停，并发凭据被释放。
- [ ] 追问一句 → 助手知道上一句问的是什么；历史不进引用编号。
- [x] 文章 3 与笔记 3 都存在时重建其中一个索引 → 另一个仍在（不误删）。
      → `tests/test_index_reconcile.py` / `tests/test_qdrant_integration.py`
- [x] `npm run check`（含前端自检 + build）、`mvn package`、Python `ruff/mypy/pytest` 全绿。
      → 均已在 2026-10-05 跑通（Python 全绿、Java `BUILD SUCCESS` 含 250 条、前端 7 组自检）

## 9. 交付记录（2026-10-05）

| 刀 | 做了什么 | 验证 |
|---|---|---|
| **P1** 流式配额收口 | `AiQaStreamController` 改为开流前 `acquireQuota`、`finally releaseQuota`（三条退出路径一起放）；触顶在**开流前**返回 429，一个下游调用都不会发出去 | `AiQaStreamControllerTest` **9 条**（新增 3 条：触顶不碰下游、正常结束释放闸门、上游失败 + 客户端断开两路都释放） |
| **P2** 语料刷新 | 新增 `AI_CORPUS_TTL_SECONDS`（默认 60s，`<=0` = 永不过期）；到期**换代**（`EPOCH++`）而不只是重读；`pipeline_for` 淘汰旧 `EPOCH` 的管道（否则每轮 TTL 多留一份语料在内存里） | `tests/test_corpus_ttl.py` **6 条**，含**隐私验收**：上游把笔记转为私有（投影行被删）后，语料与切块结果里都必须没有它 |
| **S1** 语料 / 索引接笔记 | 取数（`kind IN ('post','note')`）、切块、`chunk_id`、payload、删除、对账全部改用**文档标识 `(kind, id)`**；文章的 `chunk_id` **保持历史格式**（已建索引不必全量重嵌 —— 免费档一天 50 次嵌入跑不完）；Wiki / 写作画像 / 评测显式钉在 `ARTICLE_KINDS`（它们的表里存的是裸 `post_id`） | 语料实测：**29 篇文章 + 11 篇笔记**（39 / 53 个子块）；`ruff` / `mypy`（81 文件）/ `pytest` 全绿 |
| **S2** 契约打通 `kind` | Python `Citation.kind`（缺省 `post`）+ `IndexRebuildRequest.contentKind`；Java `CitationDTO` / `IndexRebuildRequestDTO`；前端 `openCitation` 按 `kind` 跳 `/note/:id`、引用卡片带「笔记」标识；顺手修掉**流式 401/429 判定失效**（抛的必须是 `ApiError`，否则 `isAuthError`/`isRateLimited` 认不出来） | Java `AiContractTest` **27** + 三个 controller 切片 **23** 条，`BUILD SUCCESS`；前端 `npm run check`（自检 + build）全过 |
| **S3** 助手浮层 + 多轮 | 抽出 `composables/useQaStream.js`（**可多实例**：浮层与深读页面板会同时存在，共用一个 store 时一边的 `delta` 会写进另一边的界面）与 `components/ai/QaAnswerBlock.vue` / `QaCitationList.vue`（三态语义与 `kind` 跳转各只有一份）；新增右下角浮层 `components/assistant/AssistantDock.vue`（不占一级导航，登录/注册页不出现）；多轮走 **`history` 契约**（最多 6 轮，服务端与前端各收一道）+ `sessionStorage` 会话；顺带修掉 `askStream(question, 5)` 这种**会被静默忽略**的旧调用形式（数字参数当 opts 用不会报错，只会悄悄回退默认） | Python `tests/test_qa_history_prompt.py` **8 条**（历史与记忆各自带说明、不得编号引用、6 轮上限、答案长度上限、无历史时没有该段）；Java `AiContractTest` **27** + `AiQaStreamControllerTest` **11**（新增 2 条：历史问句 trim 而答案原样透传、无历史时传空表）；前端 `npm run check` 全过（**8 组**自检 + build；新增 `scripts/qa-selfcheck.mjs` **30 条**断言，加载真 composable —— 它正是为「构建绿、运行红」那类错误加的） |
| 顺手 | 修掉**两条既存失败**（语料从种子包切到投影表那天起就一直红着，等于没有测试）：`test_style_api` 写死的种子包实测值 → 加「语料来源门控 + 更新实测值」；`test_eval_api` 写死的 `seed-sql:` 来源 → 改为与当前语料现算比对 | `pytest` 全绿 |

### 未做（按拍板顺序）

- **S4：范围限定**（只问这篇 / 某标签 / 某作者）—— 按 §5 的拍板放到二期。
- ⚠️ **环境动作**：语料里现在多了 11 篇笔记，**全量重建索引**会把整库重嵌一遍
  （免费档 embedding 每日 50 次）。建议先 `POST /ai/admin/corpus/sync`，再走
  `POST /ai/admin/index/reconcile` 只嵌「新增/变更」的那些。
  默认（内存语料通路）**不需要**重建索引就能问到笔记。
- ⚠️ **改完 Python 侧要重启 8200**：语料 TTL、笔记进语料、`history` 都在 `stellar-ink-ai` 里，
  跑着的 uvicorn 不会自己换代码（用 `deploy\scripts\start-all.bat` 从仓库根重启即可）。

