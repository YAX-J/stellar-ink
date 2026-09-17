# 星笺 AI 开发流程（人机协作）

> 三份文档的分工：`README.md` 讲**技术路线与原理**，`implementation-roadmap.md` 讲**每个阶段做什么**，
> 本文讲**怎么一轮一轮做出来** —— 节奏、分工、验证、提交、红线与决策门。
> **每轮开工前先读本文；收尾时更新 roadmap 顶部的进度清单和本文 §9。**

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

## 5. 每轮的最低验证门槛

```bash
# Python（新增代码必须全绿）
cd stellar-ink-ai && ruff check . && mypy app && pytest

# Java（改到的模块必须能打包；契约相关要跑测试）
cd stellar-ink-server && mvn -DskipTests package && mvn test

# 前端（只在动前端时跑）
cd stellar-ink-web && npm run build
```

改到真实接口后，还要按顺序起服务再验一次：
`Nacos → 三个 Java 服务（含 ai-service）→ stellar-ink-ai → 前端`，
经网关（不是直连服务）跑通改动路径；中文请求体写成 UTF-8 文件或用 Node Fetch（git-bash 的 curl 会发 GBK）。

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

## 9. 当前切片：M0（契约与工程骨架）

M0 全程用 Fake Adapter，**不需要任何密钥**，可以立刻开始。按以下五刀提交：

| 切片 | 内容 | 验收 |
|---|---|---|
| M0-1 | `stellar-ink-ai` 工程骨架：`pyproject.toml`（Py 3.11+、ruff/mypy/pytest 配置）、`app/` 分层、`/health`、更新目录 README | `ruff check . && mypy app && pytest` 全绿 |
| M0-2 | Python 侧契约：`app/schemas/` 的问答 / 写作 / 索引任务请求响应模型 + `tests/fixtures/*.json` | Schema 测试通过，fixture 可被 Java 复用 |
| M0-3 | `stellar-ink-ai-client`：`pom.xml` 并登记进 `stellar-ink-server/pom.xml`，内部 DTO、HMAC 头常量、Fallback | `mvn -DskipTests package` 通过 |
| M0-4 | `ai-service`：8107、统一启动模板、`/ai/health`、`AuthHelper` 防御性复核、Fake 下游客户端、契约测试（Java DTO ↔ 同一组 fixture） | `mvn test` 通过，契约测试命中同一 JSON |
| M0-5 | 文档同步：`docs/api` 加 `/ai/health`、`docs/architecture` 加模块与端口、`AGENTS.md` 目录树与状态、roadmap 进度清单 | 文档与实际端口/路径一致 |

迁移脚本编号提醒：`deploy/sql/` 的 `01`–`09` 已被现有功能占用，AI 相关脚本从 **`10_ai-schema.sql`**、
**`11_post-outbox.sql`** 开始（roadmap §17 里的 04/05 是旧编号，已修正）。

## 10. 待确认的选型（不阻塞 M0，M2 之前必须定）

| 项 | 候选 | 影响 |
|---|---|---|
| Chat 模型 | DeepSeek（chat / reasoner）、通义千问、OpenAI 兼容自建 | M2 的 Provider 默认配置 |
| Embedding | bge-m3（本地或云）、通义 text-embedding-v3 | 向量维度与索引结构，M3 前定死 |
| Reranker | bge-reranker-v2-m3 等 | M4 的重排链路 |
| Qdrant | 本地 Docker 自建 / 复用服务器容器 | 开发与生产的地址与密钥 |
| Python 包管理 | `uv`（推荐）或 `pip + venv` | 本地与 Docker 构建方式 |
| 服务器 GPU | 有 / 无 | M6 走 vLLM/SGLang 私有化，还是只做云模型适配 |
