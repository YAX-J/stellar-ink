# 星笺 AI 代码实施路线

> 状态：编码前实施方案。本文规定开发顺序、模块边界和每阶段验收条件；实际接口落地时再同步 `docs/api/README.md`、`docs/architecture/README.md`、`AGENTS.md` 和部署文档。
>
> 上位技术地图见 [README.md](README.md)。本文结合 2026-09-13 可公开访问的国内 AI 应用开发 JD，对原路线进行了工程化调整。

## 1. 对比后的结论

国内 AI 应用开发岗位的核心要求可以归纳为四层：

| 层级 | JD 高频要求 | 星笺落点 |
|---|---|---|
| AI 应用基础 | 模型 API、Prompt、结构化输出、Python/Java、FastAPI/Spring Boot | Model Gateway、Java AI BFF、Python AI 服务 |
| 知识应用 | 文档解析、Embedding、向量数据库、RAG、混合检索、Reranker | 文章索引、Qdrant、BM25、RRF、引用问答 |
| Agent 工程 | Function Calling、LangGraph、Memory、MCP、HITL、Checkpoint、SSE | 受控写作 Agent、工具白名单、人工确认 |
| 生产交付 | Docker、Redis、日志、评测、私有化、国产模型、vLLM/SGLang | AI Compose、Langfuse/OTel、Qwen/DeepSeek、推理压测 |

公开 JD 参考：

- [中粮信科 Agent 应用工程师](https://www.zhaopin.com/jobdetail/CC438346630J40889130810.htm)：模型 API、Prompt、RAG、向量库、Agent、知识库、后端集成。
- [AI Agent 开发](https://www.zhaopin.com/jobdetail/CCL1445690250J40888950710.htm)：LangGraph、MCP、混合检索、Reranker、HITL、Checkpoint、评测、FastAPI、SSE、私有化。
- [全栈 AI 工程师](https://www.zhaopin.com/jobdetail/CCL1337269640J40895288505.htm)：Python、TypeScript、Agent、数据库架构和全流程交付。

对原方案作三项调整：

1. **国产模型与私有化提前**：不再放在所有功能完成之后，在 RAG 稳定后立即加入 OpenAI 兼容适配和 vLLM/SGLang 实验。
2. **评测贯穿每个阶段**：不单独等到最后建设 LLMOps；每增加一个能力，同时增加数据集、指标和 Bad Case。
3. **修正 Java 模块职责**：`stellar-ink-ai-client` 保持客户端库属性，新增 `ai-service :8107` 作为对外 AI 业务服务。

## 2. 最终模块边界

```text
浏览器
  │ Authorization + JSON/SSE
  ▼
gateway-nacos-sentinel :8080
  │ /ai/**
  ▼
ai-service :8107                         Java，安全与业务边界
  ├─ Sa-Token JWT 防御性复核
  ├─ READER / AUTHOR / ADMIN 权限
  ├─ 调用 content-service 的内部文章 Feign 契约
  ├─ 请求限额、审计、统一 Response
  └─ stellar-ink-ai-client               Java → Python 客户端库
           │ HMAC 内网签名 + traceId
           ▼
stellar-ink-ai :8200                     Python，AI 领域服务
  ├─ Model Gateway
  ├─ Index / RAG / Eval
  ├─ Agent / Memory / Wiki
  └─ Qdrant + Redis + ai_* 表
```

边界规则：

- 浏览器只访问网关，不直连 Python。
- Python 不解析 Sa-Token，也不读取 `user`、`post` 等现有业务表。
- Java 将 `userId`、`role`、`traceId` 通过带时间戳的 HMAC 内部请求传给 Python。
- Python 只写自己拥有的 `ai_*` 表和 Qdrant collection。
- 文章数据由 `content-service` 的内部契约提供；草稿只随当前作者请求临时传输，不进入公共索引。
- 第一版 Agent 工具全部只读；文章写入继续使用现有 `/posts/**` 接口并由用户确认。

### 2.1 为什么不让网关直接路由 Python

直接路由代码更少，但 Python 需要复制 Sa-Token JWT 规则、角色语义和异常协议，容易造成两套鉴权实现。Java `ai-service` 多一跳，却能复用现有安全、Trace、Fallback 和 `Response<T>` 约定，更适合当前仓库。

### 2.2 为什么第一版 RAG 不直接使用完整框架

第一版用薄封装实现切块、召回、RRF、重排和引用，便于学习底层并测试每一层。LangGraph 在进入有状态 Agent 时引入；LlamaIndex 仅在复杂文档连接器确有收益时引入。这样既能满足 JD 中的框架经验，也不会让核心逻辑完全藏在框架中。

## 3. 总体编码顺序

| 阶段 | 交付物 | 主要技术 | 求职能力门槛 |
|---|---|---|---|
| M0 | 契约、目录和测试骨架 | OpenAPI、Pydantic、JUnit、pytest | 工程基础 |
| M1 | Java/Python 调用链 | FastAPI、Feign、HMAC、TraceId | 后端集成 |
| M2 | 多模型网关 | 结构化输出、流式、模型路由 | LLM API 开发 |
| M3 | Dense RAG MVP | Embedding、Qdrant、HNSW、引用 | 初级 AI 应用岗 |
| M4 | Advanced RAG | BM25、RRF、Reranker、评测 | 中级 RAG 岗 |
| M5 | 问答与写作前端 | Vue、Pinia、Fetch SSE、Diff | AI 全栈能力 |
| M6 | 国产模型私有化 | Qwen/DeepSeek、vLLM/SGLang、量化 | 国内交付能力 |
| M7 | 可控单 Agent | LangGraph、Tools、HITL、Checkpoint | Agent 应用岗 |
| M8 | MCP 与生产观测 | MCP、Langfuse/OTel、配额、审计 | 高级应用/平台岗 |
| M9 | Memory | 记忆抽取、冲突、删除、个性化 | 个性化应用 |
| M10 | GraphRAG / Wiki | 实体消歧、主张、图、社区发现 | 差异化能力 |
| M11 | 多模态与微调实验 | OCR、PDF、语音、LoRA/QLoRA | 专项扩展 |

必须按阶段验收。M0 至 M5 没有完成前，不并行开发多 Agent、GraphRAG 或微调。

## 4. M0：契约和工程骨架

### 目标

建立可构建、可测试但暂不调用真实模型的三个模块，先冻结边界。

### 代码步骤

1. 在 `stellar-ink-server/pom.xml` 登记：
   - `stellar-ink-ai-client`
   - 新建 `ai-service`
2. 为 `stellar-ink-ai-client` 创建 `pom.xml`，只放 Feign 契约、内部 DTO 和 Fallback。
3. 创建 `ai-service` 标准 Spring Boot 模块：
   - `controller/`
   - `service/`、`service/impl/`
   - `config/`
   - `pojo/`、`mapper/` 仅在确实需要 Java 持久化时再建。
4. 在 `shared-model` 增加对外 AI DTO/VO；Java 与 Python 私有协议 DTO 留在 `stellar-ink-ai-client`。
5. 为 `stellar-ink-ai` 创建：

```text
stellar-ink-ai/
├── pyproject.toml
├── app/
│   ├── main.py
│   ├── api/v1/
│   ├── core/
│   ├── providers/
│   ├── embedding/
│   ├── rag/
│   ├── agents/
│   ├── schemas/
│   └── vectorstore/
├── tests/
├── scripts/
└── Dockerfile
```

6. 固定 Python 格式、静态检查和测试命令：`ruff`、`mypy`、`pytest`。
7. 先写接口契约测试，再写空实现；模型、Qdrant、Redis 均使用 Fake Adapter。

### 暂定接口

以下接口只是实施契约，真正实现时必须同步到 `docs/api/README.md`：

| 方法 | 路径 | 角色 | 用途 |
|---|---|---|---|
| GET | `/ai/health` | 公开 | Java AI 服务健康状态，不泄露配置 |
| POST | `/ai/qa/stream` | READER | 基于公开文章的流式问答 |
| POST | `/ai/writing/suggest` | AUTHOR | 对当前作者草稿生成建议 |
| POST | `/ai/admin/index/rebuild` | ADMIN | 触发全量索引任务 |
| GET | `/ai/admin/jobs/{id}` | ADMIN | 查询索引任务状态 |

### 验收

- Java 全量 `mvn package` 通过。
- Python 静态检查和单元测试通过。
- Java Contract Test 与 Python Schema Test 使用同一组 JSON fixture。
- 没有真实密钥、默认生产密钥或业务表直连。

### 本阶段学习

OpenAPI/JSON Schema、Pydantic、Feign、依赖倒置、契约测试和同步/流式接口差异。

## 5. M1：Java/Python 安全调用链

### 目标

打通 `Vue/curl → Gateway → ai-service → Python`，暂时只回显经过校验的模拟结果。

### 代码步骤

1. `ai-service` 使用项目统一启动模板，端口 `8107`，注册 Nacos。
2. 网关 dev/prod 显式增加 `/ai/** → lb://ai-service`，保持 discovery locator 关闭。
3. 在网关鉴权中单独处理 AI 权限：
   - AI 问答 POST 需登录。
   - 写作建议需 `AUTHOR`。
   - `/ai/admin/**` 需 `ADMIN`。
4. `ai-service` 内再次调用 `AuthHelper.requireAtLeast()`，不能只依赖网关。
5. `stellar-ink-ai-client` 使用 Feign 或 WebClient 调 Python 内网地址。
6. 内部请求签名包含：
   - `X-Trace-Id`
   - `X-AI-User-Id`
   - `X-AI-Role`
   - `X-AI-Timestamp`
   - `X-AI-Nonce`
   - `X-AI-Signature`
7. Python 校验 HMAC、时间窗和 nonce，拒绝重放；密钥使用 `AI_INTERNAL_SECRET` 环境变量。
8. 打通 SSE 心跳、完成、错误和取消事件。

### SSE 事件约定

```text
event: meta      请求与模型元数据
event: delta     增量文本
event: citation  引用信息
event: done      用量、耗时和结束原因
event: error     可展示错误码，不包含堆栈和密钥
```

### 测试

- JWT 缺失、角色不足、伪造身份头均被拒绝。
- HMAC 过期、篡改和 nonce 重放均被拒绝。
- Python 超时后 Java 正确关闭 SSE 并返回可识别错误。
- `X-Trace-Id` 能贯穿网关、Java 和 Python 日志。

### 验收

通过网关完成一次模拟流式响应；浏览器取消请求后，下游任务也被取消，不继续消耗资源。

## 6. M2：多模型网关与结构化生成

### 目标

先抽象模型能力，再开发具体业务，避免代码绑定一家厂商。

### 代码结构

```text
app/providers/
├── base.py                    ChatModel 抽象
├── capabilities.py            tools/json_schema/streaming 等能力
├── openai_compatible.py       通用兼容协议
├── provider_registry.py       配置驱动注册
└── model_router.py            按任务、质量、费用路由
```

### 代码步骤

1. 定义 `ChatModel`、`EmbeddingModel`、`RerankModel` 三类接口，不用一个万能类。
2. 第一版实现一个 OpenAI 兼容 Provider；具体厂商名称只进入配置。
3. 配置至少四个逻辑角色：
   - `FAST_MODEL`
   - `CHAT_MODEL`
   - `REASONING_MODEL`
   - `EMBEDDING_MODEL`
4. 实现模型能力探测，不能假设每个兼容 API 都支持工具调用和 JSON Schema。
5. 所有结构化结果经过 Pydantic 二次校验；解析失败允许有限重试，不无限自修复。
6. 记录输入/输出 Token、TTFT、总耗时、结束原因和模型标识。
7. 使用 Fake Provider 完成确定性单元测试，真实模型测试标记为 integration。

### 测试

- Provider 契约测试：普通生成、流式、超时、限流、非法 JSON、工具调用。
- Router 测试：简单任务不错误路由到高成本模型。
- 密钥不出现在日志、异常响应和 Actuator 中。

### 验收

同一个写作摘要用 Fake Provider 和至少一个真实 Provider 跑通，业务层不出现厂商 SDK 类型。

## 7. M3：Dense RAG 最小闭环

### 目标

完成“文章发布内容 → Qdrant → 检索 → 带引用答案”的最小闭环。

### 代码步骤

1. 在 `content-service` 的文章领域增加仅供 AI 使用的内部只读契约：按 ID/更新时间分页读取已发布文章。
2. 在 AI 实施阶段新增专用的文章查询契约模块和 Feign 客户端；`stellar-ink-ai-client` 仍只负责 Java → Python。
3. `ai-service` 拉取文章并转发给 Python Index API，Python 不直连 `post` 表。
4. Python 实现规范化和父子切块：
   - 保留标题、标签、作者、发布时间和段落序号。
   - child chunk 用于召回，parent chunk 用于生成上下文。
   - 保存内容哈希、文章版本和 chunk 版本。
5. 调用 Embedding Provider，写入 Qdrant `stellar_post_chunks_v1`。
6. 使用 metadata filter 强制 `status=published`。
7. 实现 Dense Top-K 检索、上下文预算和基础拒答。
8. 引用至少包含 `postId`、标题、段落序号和文本片段。

### 数据一致性

MVP 先提供 ADMIN 全量重建和按文章重建。不要在这一阶段直接上消息队列；先证明索引正确，再在 M4 引入 Outbox 增量同步。

### 测试

- 中文标点、空段落、超长段落和重复段落切块测试。
- 同一文章重复索引必须幂等。
- 删除或转草稿后公共检索不能命中。
- 引用的 chunk 必须属于返回的文章和当前版本。
- 准备不少于 30 个问题的第一版黄金集，其中包含无答案问题。

### 验收

- 从公开文章提出问题可以返回原文引用。
- 无证据问题明确拒答。
- `Recall@5`、引用正确率和 P95 延迟有第一版基线，而不是只做主观演示。

## 8. M4：Advanced RAG 与增量索引

### 目标

把 Demo 级向量问答升级为可解释、可评测的检索系统。

### 代码步骤

1. 增加 BM25 或 Qdrant Sparse 检索。
2. 用 RRF 融合 Dense 与 Sparse 排名，保留各路原始分数。
3. 增加 Reranker，限制候选数和超时预算。
4. 增加 Query Rewrite 和 Multi-Query，但必须保留原始问题并限制扩写数量。
5. 增加 Contextual Retrieval：为 child chunk 补标题和父级摘要。
6. 在 `content-service` 的文章领域使用 Outbox Pattern 记录发布、更新、删除事件。
7. Index Worker 消费事件，按内容哈希幂等更新索引；失败进入可重试状态。
8. 增加 Semantic Cache，但缓存键必须包含权限范围、索引版本和模型版本。
9. 建立离线评测 CLI，输出 Dense、Hybrid、Hybrid+Rerank 对比报告。

### 测试与指标

- `Recall@K`、`MRR`、`NDCG`。
- Context Precision、Answer Faithfulness、Citation Accuracy。
- 无答案拒答率。
- 索引事件积压、失败重试和最终一致性。
- 以固定黄金集证明高级链路优于 M3，而不是默认“组件越多越好”。

### 验收

只有当混合检索或重排在固定数据集上带来可量化提升，才保留对应组件；否则回退，避免徒增成本。

## 9. M5：前端问答与写作 Copilot

### 目标

形成国内 JD 最看重的端到端 AI 应用项目。

### 代码步骤

1. 新建 `src/stores/ai.js`，所有 AI 请求和状态统一进入 Pinia。
2. 扩展 `src/api/client.js`，增加 Fetch SSE 解析、取消、超时和事件校验；不使用原生 `EventSource`，因为当前 token 存储方式需要自定义请求头。
3. 深读页增加文章问答区域：
   - 流式答案。
   - 可点击引用并定位原段落。
   - 取消、重试和错误状态。
4. 执笔页增加 Copilot：
   - 标题、提纲、润色、续写、标签和摘要。
   - 请求中携带当前文本，不把草稿写入公共索引。
   - 输出差异预览，用户点击接受后才修改本地编辑器。
5. 所有新视觉遵循现有主题变量和原型气质，不引入 UI 组件库。
6. 记录建议是否采纳，但不记录未授权的完整草稿内容。

### 测试

- SSE 分片边界、中文字符、断线、取消和重复事件测试。
- READER 不能调用写作建议。
- AI 建议不能绕过原有文章发布权限。
- night/dusk/dawn 三主题和移动端布局验证。

### 验收

完成两条可演示用户路径：

```text
阅读文章 → 提问 → 流式回答 → 点击引用定位原文
编辑草稿 → 请求润色 → 查看差异 → 接受局部修改 → 手动发布
```

完成 M0 至 M5 后，项目已经足以覆盖大多数初级和一部分中级 AI 应用开发 JD。

## 10. M6：国产模型与私有化推理

### 目标

补齐国内岗位明显强调的国产模型、私有化和推理服务能力。

### 代码步骤

1. 为 Model Gateway 增加至少一个国产云模型适配配置。
2. 选择一个可公开部署的 Qwen 或 DeepSeek 系列开源模型做本地实验；具体型号按当时硬件和许可证重新确认。
3. 分别用 vLLM 或 SGLang 暴露 OpenAI 兼容 API，业务代码不变，只切换 Provider 配置。
4. 记录并比较：
   - 首 Token 延迟 TTFT。
   - 输出 Token/s。
   - P50/P95 延迟。
   - 并发吞吐。
   - GPU 显存和主机内存。
   - 每百万 Token 或每请求成本。
5. 对比 BF16/FP16、8-bit 和 4-bit 量化的质量与资源消耗。
6. 云端模型失败时，可按策略降级到本地模型；高风险任务不静默更换模型。
7. 生产服务器资源不足时，本地推理部署在独立 GPU 环境，星笺服务器只访问兼容 API。

### 验收

- 同一套 Provider Contract Tests 同时通过云模型和本地兼容服务。
- 输出一份真实压测报告，而不是只证明模型能够启动。
- 明确许可证、硬件、质量、成本和隐私的取舍。

## 11. M7：可控单 Agent

### 目标

用 LangGraph 实现有状态、可恢复的单 Agent，不从多 Agent 起步。

### 第一批只读工具

- `search_posts`
- `get_post`
- `get_related_posts`
- `compare_posts`
- `get_author_public_profile`

### 状态图

```text
START
  → classify_intent
  → plan
  → retrieve_or_call_tool
  → validate_tool_result
  → synthesize
  → verify_citations
  → END
```

### 代码步骤

1. 定义 Agent State，包含用户范围、任务、步骤、工具结果、引用、预算和错误。
2. 每个 Tool 使用 Pydantic Schema；模型输出不能直接拼接 SQL、URL 或内部路径。
3. 设置最大步数、Token 预算、超时和每工具调用次数。
4. 使用 Redis 或持久化 Checkpointer 保存可恢复状态。
5. 在需要写入、外部请求或高成本操作前触发 Interrupt。
6. 用户确认后由 Java 执行允许的业务操作；Python 不直接写业务表。
7. 建立 Agent 评测集：工具选择、参数、任务成功、循环、越权和引用。

### 验收

- Prompt Injection 测试不能诱导 Agent 调用未授权工具。
- Agent 中断后可以 Resume，重复恢复不造成重复副作用。
- 任意任务都在预算耗尽前结束并给出明确状态。

## 12. M8：MCP、观测和生产保护

### 目标

把工具能力标准化，并让每次 AI 调用可追踪、可回放、可限额。

### 代码步骤

1. 将第一批只读文章工具封装成 MCP Server。
2. MCP Tool 定义 Schema、权限标签、超时、幂等性和审计字段。
3. MCP 只是协议层，不能替代 Java 权限和工具白名单。
4. 接入 OpenTelemetry；可选部署 Langfuse 保存 Prompt、模型调用、检索和 Tool Trace。
5. Redis 实现用户/角色/模型维度的配额和并发限制。
6. 增加熔断、重试、降级、缓存和后台任务监控。
7. 管理端只能看到脱敏后的 Prompt 与内容摘要。
8. 建立 Prompt Registry：名称、版本、变量、Schema、适用模型和评测结果。

### 验收

- 可以从一个 traceId 回放网关、Java、检索、模型和工具链路。
- 配额、超时、熔断和 Provider 降级均有自动化测试。
- MCP 客户端无法通过自行构造参数扩大数据权限。

完成 M0 至 M8 后，项目可以较完整地对应中级 AI 应用和 Agent 工程岗位。

## 13. M9：作者记忆和个性化

### 目标

实现用户可控的长期写作记忆，而不是简单保存全部聊天记录。

### 数据模型

- `ai_memory`：类型、内容、来源、置信度、状态、创建和过期时间。
- `ai_memory_evidence`：关联文章或用户确认记录。
- `ai_style_profile`：可解释的风格特征和版本。

### 代码步骤

1. 当前对话仅作为工作记忆，限制上下文长度。
2. 模型只生成“记忆候选”，规则和用户确认决定是否持久化。
3. 写入前进行相似去重、矛盾检测和敏感信息检查。
4. 召回时按用户、类型、时间、相关度和可信度过滤。
5. 前端提供查看、纠正、删除、禁用和全部清除。
6. 删除后同步清除向量、缓存和派生风格画像。

### 验收

- 用户 A 的记忆不会被用户 B 召回。
- 删除和禁用具有端到端测试。
- 模型不能在没有证据时把推测写成永久用户事实。

## 14. M10：GraphRAG 与 LLM Wiki

### 目标

建立带来源、可增量更新的主题知识图，而不是一次性模型摘要。

### 数据模型

- `ai_wiki_entity`
- `ai_wiki_alias`
- `ai_wiki_relation`
- `ai_wiki_claim`
- `ai_wiki_evidence`
- `ai_wiki_page_version`

### 代码步骤

1. 从已发布文章抽取实体、别名、关系和原子主张。
2. 使用规则、向量和 LLM 组合进行实体消歧；低置信项进入 ADMIN 审核。
3. 每条主张绑定文章版本、段落和原文片段。
4. 使用 Louvain 或 Leiden 做主题社区发现。
5. 实现 Local Search：围绕实体和邻居回答具体问题。
6. 实现 Global Search：使用社区摘要回答跨文章主题问题。
7. 加入时间字段，展示主题、人物或观点的演变。
8. 文章更新时只失效受影响的主张、关系和 Wiki 页面。
9. 前端将主题星图与现有 Canvas 规范结合，所有节点都有来源入口。

### 验收

- Wiki 的事实性文本必须能回到证据。
- 实体合并、拆分和错误纠正不会破坏历史版本。
- GraphRAG 必须在跨文章问题集上与普通 RAG 对比，证明收益后再保留。

## 15. M11：多模态和微调实验

这些是独立扩展，不阻塞主线。

### 多模态

1. PDF 先做版面解析，再按标题、段落、表格和页码索引。
2. 图片先做 OCR 和视觉描述，再建立图文向量。
3. 语音使用流式 ASR，增量整理后保存为待确认草稿。
4. 文件上传能力需单独完成安全设计后才能开放，不借 AI 功能绕过当前边界。

### 微调

1. 先收集“输入、AI 建议、作者修改、最终版本”的授权数据。
2. 建立训练/验证/测试集，避免同一文章泄漏到多个集合。
3. 先比较 Prompt、Few-shot、RAG 和风格画像基线。
4. 只有基线不能满足需求时再做 LoRA/QLoRA。
5. DPO/GRPO 只用于明确的偏好或推理目标，不用于灌输经常变化的事实。

## 16. 测试金字塔

```text
E2E：浏览器 → 网关 → Java → Python → Qdrant/模型
Contract：Java DTO ↔ Python Pydantic ↔ SSE 事件
Integration：Qdrant、Redis、MySQL、真实 Provider
Unit：切块、RRF、路由、权限、签名、引用、状态图
Eval：黄金集、Bad Case、事实性、工具成功率、成本和延迟
```

每阶段最低验证命令：

```bash
# Python
cd stellar-ink-ai
ruff check .
mypy app
pytest

# Java
cd stellar-ink-server
mvn -DskipTests package
mvn test

# 前端
cd stellar-ink-web
npm run build
```

修改真实接口后，还必须启动对应服务并通过网关测试；中文请求体使用 UTF-8 文件或 Node Fetch。

## 17. 数据库和索引迁移顺序

1. `04_ai-schema.sql`：仅创建 `ai_*` 表。
2. `05_post-outbox.sql`：由 `content-service` 文章领域拥有的索引事件表。
3. Qdrant collection 使用版本后缀，如 `stellar_post_chunks_v1`。
4. 新索引先全量构建并评测，完成后通过 alias 原子切换。
5. 回滚只切回旧 alias，不在失败部署中删除旧 collection。
6. 数据库、Qdrant 和缓存删除流程都要支持用户数据清除。

## 18. 配置与密钥

新增配置全部使用环境变量并通过 Nacos 管理非敏感动态项：

```text
AI_SERVICE_URL
AI_INTERNAL_SECRET
AI_PROVIDER
AI_API_BASE
AI_API_KEY
AI_FAST_MODEL
AI_CHAT_MODEL
AI_REASONING_MODEL
AI_EMBEDDING_MODEL
AI_RERANK_MODEL
QDRANT_URL
QDRANT_API_KEY
REDIS_HOST
REDIS_PORT
```

生产要求：

- `AI_INTERNAL_SECRET` 和模型密钥无默认值，缺失时拒绝启动相关能力。
- 日志、Actuator、异常和前端构建产物中不能出现密钥。
- 模型配置变更记录操作者和版本。
- 外部模型调用前执行数据分类、脱敏和用户授权检查。

## 19. 建议的提交拆分

AI 改动跨度大，每个提交只包含一个可验证主题。提交由用户执行，建议格式：

```text
feat(ai): 搭建 Python AI 服务与健康检查
feat(ai-client): 增加 Python 服务 Feign 契约
feat(ai-service): 打通 AI 鉴权与内部签名调用
feat(rag): 建立文章切块与 Qdrant 索引
feat(rag): 增加混合检索与重排评测
feat(web): 接入文章问答与流式引用
feat(web): 增加写作建议差异确认
feat(inference): 接入国产模型兼容服务
feat(agent): 增加可恢复的只读写作 Agent
feat(mcp): 发布只读文章工具服务
feat(wiki): 建立带证据的主题知识图
```

每个提交同时包含对应测试；接口、部署或目录变化同步文档。

## 20. 开发决策门

进入下一阶段前必须回答“是”：

### M3 → M4

- 引用能稳定定位原文吗？
- 有黄金问题集和无答案问题吗？
- 索引更新和删除幂等吗？

### M4 → M5

- 高级检索相对 Dense 基线有量化提升吗？
- 评测可以重复运行吗？
- 单次请求成本和延迟可接受吗？

### M5 → M6/M7

- 前端取消能终止下游生成吗？
- AI 不能绕过文章权限吗？
- 错误、超时和降级路径完整吗？

### M7 → M8

- Agent 有步数、Token 和时间预算吗？
- Tool Schema、权限和参数都有服务端校验吗？
- Interrupt/Resume 不会重复执行副作用吗？

### M8 → M9/M10

- 每次模型、检索和工具调用可追踪吗？
- 用户数据可以完整删除吗？
- 已经有足够真实使用数据证明需要记忆或知识图吗？

## 21. 求职阶段成果

| 完成范围 | 可以证明的能力 |
|---|---|
| M0-M3 | Python/Java AI 服务、模型 API、Embedding、向量库和基础 RAG |
| M0-M5 | 端到端 AI 应用、Advanced RAG、SSE、Vue 和可量化评测 |
| M0-M6 | 国产模型、OpenAI 兼容协议、私有化推理和性能分析 |
| M0-M8 | LangGraph、MCP、HITL、Checkpoint、可观测和生产保护 |
| M0-M10 | Memory、GraphRAG、证据 Wiki 和增量知识工程 |

目标是每个阶段都能回答三类面试问题：

1. **原理**：它为什么有效，底层数据结构或控制流是什么。
2. **取舍**：为什么选它而不是旧方案或另一个框架。
3. **证据**：测试、指标、Trace、压测或用户行为如何证明结果。

最终优先级为：先完成 M0 至 M5，形成可运行的 AI 应用主线；再完成 M6 和 M7，补齐国内岗位需要的私有化与 Agent；之后根据真实数据决定是否继续 MCP、Memory、GraphRAG 和微调。
