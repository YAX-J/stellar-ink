# 星笺 AI 阶段状态（截至 A→E 第 20 轮）

> 这份文件是**核验报告**，不是宣传：每一行都对应仓库里可运行的命令或可读的文件。
> 有疑问的地方一律写成「未做 / 待验证」，不写成「已完成」。
> 排序与口径以 `docs/ai/fast-track-plan.md` 为准，工程约定以 `AGENTS.md` 为准。

## 1. 一句话结论

**A / B / C / D 四阶段与 E1、E2 已完成并可执行验证；E3（MCP 与观测）、E4（GraphRAG）未开始。**
另有三处**已知缺口**写在 §4，它们不影响「功能可用」，但影响「可以放心用真实模型与真实流量」。

## 2. 逐阶段核验

| 阶段 | 状态 | 关键产物 | 核验方式 |
|---|---|---|---|
| A1 模型配置中心 | ✅ | `ai_*` 表 + AES-GCM 密钥加密 + Provider CRUD 脱敏 + Provider 层 + `/ai-lab` 面板 | `mvn -pl ai-service -am test`；`tests/test_internal_auth_wiring.py` |
| A2 安全调用链 | ✅ | 网关 `/ai/**` 路由与角色门槛；内部 HMAC（**标准串含身份字段**）+ 纯 ASGI 验签 | `mvn -pl gateway-nacos-sentinel -am test`；签名向量 `tests/fixtures/signature_vector.json` |
| B1 Qdrant 适配 | ⚠️ 代码完成、**真实冒烟未跑** | `app/rag/qdrant_store.py`（幂等 point id、错误分类、`trust_env=False`） | `tests/test_qdrant_store.py`（32 条 MockTransport 协议）；见 §4.2 |
| B2 切块索引 | ✅ | `app/rag/chunking.py`（父子块、标题路径、锚点、内容哈希） | `tests/test_chunking.py` |
| B3 混合检索 | ✅ | `app/rag/pipeline.py` + `retrieval.py`（BM25 / 余弦 / RRF / 可开关重排） | `uv run python scripts/eval_local_baseline.py`；`scripts/compare_strategies.py` |
| C 评测台 | ✅ | 指标层 + 黄金集 v1（30 题）+ 策略对比 + `/ai/admin/eval/**` + `/ai-lab?tab=eval` | 同上两条脚本；`tests/test_eval_api.py`；`AiEvalControllerTest` |
| D1/D2 问答 | ✅ | `app/rag/qa.py` + `/ai/qa` + 深读页「问星笺」 | `tests/test_qa*.py`；`AiQaControllerTest` |
| D2s 流式问答 | ✅ | `/qa/stream` 事件契约 + `ResponseBodyEmitter` 出口 + `utils/sse.js` 流式渲染与中止 | `tests/test_qa_stream*.py`；`HttpQaStreamClientTest`；`npm run check` |
| D3 Copilot | ✅ | `app/rag/writing.py` + `/ai/writing/suggest` + 执笔页差异预览与人工采纳 | `tests/test_writing*.py`；`AiWritingControllerTest`；`npm run check` |
| E1 写作记忆 | ✅ | `app/rag/style.py`（**不引用原句**）+ `/ai/writing/style` + 只读画像面板 | `tests/test_style*.py`；`AiWritingStyleControllerTest` |
| E2 只读 Agent | ✅ 核心（**前端入口未接**） | `app/rag/agent.py`（三维预算 + 引用核实 + 中断）+ 只读工具 + `/ai/agent/ask` | `tests/test_agent*.py`；`AiAgentControllerTest` |
| E3 MCP 与观测 | ❌ **未开始** | — | — |
| E4 GraphRAG / LLM Wiki | ❌ **未开始** | — | — |

## 3. 一次完整核验的命令与结果

```bash
# Python：静态检查 + 类型 + 测试
cd stellar-ink-ai
uv run ruff check .          # All checks passed!
uv run ruff format --check . # 95 files already formatted
uv run mypy app              # Success: no issues found in 49 source files
uv run pytest                # 442 passed（含脚本自检 15 条）

# 可执行的检索/评测证据（不需要 Qdrant、不需要任何 API Key）
uv run python scripts/eval_local_baseline.py   # 29 篇 / 41 子块；Recall@1 0.8333、NDCG@5 0.9485、拒答率 0.4、误拒率 0.0
uv run python scripts/compare_strategies.py    # 四组策略排序；dense 与 hybrid+rerank 相同 → 重排必须换模型
uv run python scripts/check_golden_evidence.py # 20 道有答案题证据自检：证据不足 0 道

# Java：契约、切片、公共模块
cd stellar-ink-server
mvn -pl ai-service -am test    # BUILD SUCCESS：ai-service 84 / client 40 / common-core 27
mvn -pl common-components/common-core,user-service,content-service,gateway-nacos-sentinel -am test  # BUILD SUCCESS

# 前端：纯逻辑自检 + 部署前缀核对 + 构建
cd stellar-ink-web
npm run check                  # 差异/采纳/SSE 切帧 46 条 + 部署自检 + vite build
```

对照基线（与阶段 C 收口时的记录一致，说明这期间没有静默退化）：
`Recall@1 0.8333 / Recall@3 0.9417 / Precision@5 0.800 / NDCG@5 0.9485 / MRR 0.975 /
拒答率 0.4 / 误拒率 0.0 / 引用准确率 1.0`。

## 4. 已知缺口（**都要在接真实流量前处理**）

### 4.1 真实 Provider 已接进编排，但**还没填过真 Key 跑一轮**

面板是模型的**唯一来源**，代码里没有任何默认模型（`fake` 也要在面板里显式选）。装配链路：

- `app/providers/runtime.py`：全进程唯一的 `ProviderResolver`（配置指纹缓存 + 换配置即换实例），
  外加 `require_roles()` 预检（缺角色时一次说清缺哪些、去哪儿填）；
- `app/rag/corpus.py`：语料唯一缓存（`EPOCH` 版本号参与检索管道的缓存键）；
- `app/api/v1/assembly.py`：`pipeline_for()` 按「语料版本 + 检索开关 + 配置指纹」缓存检索管道
  （整库嵌入因此只发生一次），`assembly_error()` 把装配失败统一翻成 400；
- 问答 / 流式问答 / Copilot / 画像 / Agent / 评测**全部**走这条路径；
  `ProviderError` 由 `app/main.py` 的全局处理器转成 429（限流）/ 400（配置）/ 502（上游）。

仍然算缺口的部分：**没有用真实模型（bge-m3 / deepseek）跑过一次**。
所有指标（含 §3 的基线）目前都是**离线口径**（种子语料 + 显式 fake），不代表真实模型质量；
Qdrant 与嵌入模型的真实往返也还没做（见 §4.2）。填完面板后的第一件事应是：
在一个小策略集上跑评测台，标定 `minDenseScore`（`scripts/calibrate_min_score.py` 也是为此）。

### 4.2 Qdrant 从未连过真实实例

`app/rag/qdrant_store.py` 有 32 条协议测试（MockTransport）与 5 条内存模拟器端到端测试，
但**没有一次真实往返**。`uv run python scripts/qdrant_smoke.py` 尚未执行过。
连接方式已查清（只绑宿主机 `127.0.0.1:6333`、无鉴权、本地走 SSH 隧道，见
`deploy/docker/README.md` 第十节），缺的只是「有人连上去跑一次」。
影响：B 阶段只能说「代码完成」，不能说「实测通过」。

### 4.3 E2 的 Agent 没有前端入口

后端与网关都已就绪（`POST /ai/agent/ask`，登录即可），但没有任何页面调用它。
这是刻意的：Agent 比一次问答慢、也更贵（可能多次调用模型），
在配额与真实模型接通之前把它挂到界面上，等于给用户一个会烧钱的按钮。

### 4.4 另外两件小事

- **前端只对 `utils/` 里的纯逻辑做了自检**，`AiLabView` / `CopilotPanel` 的交互没有自动化测试
  （没有引入测试运行器，见 `AGENTS.md` §3）。这些面板的验证目前靠 `npm run check` 的构建 + 人工。
- **配额与 `retrievalAudit` 未实现**：`/ai/**` 只有网关的全局限流与 Nginx 的 `ai` 档（30r/m），
  没有按用户/按模型的成本账。

## 5. 需要使用者做的事（代码之外）

1. 在服务端 `.env` 里设置 `AI_SECRET_MASTER_KEY`（32 字节 base64）与 `AI_INTERNAL_SECRET`（≥32 字符）——
   两者都**没有默认值**，缺失时相关能力直接拒绝，不会静默降级。
2. 执行 `mysql -u root -p stellar_ink < deploy/sql/10_ai-schema.sql`（`ai_*` 表）。
3. 在 `/ai-lab` 面板里填 API Key（只写不读，列表只回掩码）并跑一次连通性自检。
4. 跑一次 Qdrant 真实冒烟（§4.2 的命令）。

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
