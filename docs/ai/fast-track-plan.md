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
   D1 AI 实验室页：所有开关（检索策略/召回数/重排/切块参数）+ 单次查询试跑
   D2 深读页问答（SSE + 引用定位）
   D3 执笔页 Copilot（差异预览 + 手动接受）

E. 扩展（按需）
   E1 写作记忆与风格画像
   E2 只读单 Agent（状态图 + 预算 + 中断恢复）
   E3 MCP 工具服务与观测（配额、审计、成本看板）
   E4 GraphRAG / LLM Wiki
```

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

## 5. 起步顺序（立即执行）

1. **A1-1** ✅ `ai_*` 表与密钥加密：`10_ai-schema.sql`（provider 配置表 + 评测表骨架）、
   Java 侧 AES-GCM 工具与跨语言测试向量、Python 侧同款解密。
2. **A1-2** ✅ Provider 配置的 CRUD 与脱敏输出（Java），包含「测试连接」转发（当前为 TCP 自检）。
3. **A1-3** ✅ Python Provider 层：OpenAI 兼容 chat/embedding/rerank 三个接口 + Fake 实现 + 按角色路由。
4. **A1-4** ✅ 前端「AI 实验室 → 模型配置」面板：选 provider、填 Key、跑自检。
5. **A2** ⏳ 网关 `/ai/**` 路由 + 角色门槛 + HMAC 内网签名（Python 侧读运行时配置）。
6. 之后进入 B（检索内核）与 C（评测台）。

### A 阶段的落地记录（供后续切片对照）

| 能力 | 位置 | 说明 |
|---|---|---|
| 密钥加密 | `common-core/crypto/AesGcmCipher`、`app/core/crypto.py` | AES-256-GCM，密文 `v1:<nonce>:<ct+tag>`；主密钥仅环境变量 |
| 跨语言一致性 | `tests/fixtures/key_vector.json` | Java 生成、两侧单测共读；改格式两侧同时红 |
| 配置 CRUD | `ai-service` 的 `AiProviderAdminController` | 五个接口全 ADMIN；**没有回读明文 Key 的接口** |
| 连通性自检 | `ProviderConnectivityChecker` | 只做 TCP 可达（`scope: tcp_only`），不冒充「模型可用」 |
| 供应商层 | `app/providers/` | 三类接口 + OpenAI 兼容实现 + 确定性 Fake + 按角色路由 |
| 前端面板 | `views/ai/AiLabView.vue`、`stores/ai.js` | 头像菜单入口（仅 ADMIN），预填常见厂商端点与模型名 |

**A 阶段修掉的三个既有缺陷**（都在鉴权链路上，值得记住）：
`ai-service` 漏配 `SaTokenConfigure`（角色判断变 500）；`AiModelRole` 未声明 JSON 字面量
（前端传 `"chat"` 变 400）；`AuthHelper` 未兜住 Sa-Token 异常（无 token 时 500 而不是 401）。
