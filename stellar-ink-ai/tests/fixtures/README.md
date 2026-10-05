# 跨语言共享契约样例（M0-2）

这里每个 JSON 都是**同一份**被两侧读取的契约样例：

| 文件 | 对应契约 |
|---|---|
| `qa_stream_request.json` | `QaStreamRequest`（`POST /ai/qa/stream` 请求） |
| `qa_answer.json` | `QaAnswer`（问答结果，引用 + 用量） |
| `writing_suggest_request.json` | `WritingSuggestRequest`（`POST /ai/writing/suggest` 请求） |
| `writing_suggest_result.json` | `WritingSuggestResult`（写作候选） |
| `writing_style_request.json` | `WritingStyleRequest`（`POST /ai/writing/style` 请求） |
| `writing_style_result.json` | `WritingStyleResult`（写作画像；由 `scripts/gen_style_fixture.py` 用**固定样本**生成，不读种子语料） |
| `agent_ask_request.json` | `AgentAskRequest`（`POST /ai/agent/ask` 请求） |
| `agent_ask_result.json` | `AgentAskResult`（只读 Agent 结果；由 `scripts/gen_agent_fixture.py` 构造「预算触顶但仍带回引用」的形态） |
| `index_rebuild_request.json` | `IndexRebuildRequest`（`POST /ai/admin/index/rebuild` 请求） |
| `index_job.json` | `IndexJob`（`GET /ai/admin/jobs/{id}` 返回） |
| `eval_run_request.json` | `EvalRunRequest`（`POST /eval/run` 请求，面板跑评测用；Java 侧转发时读同一份） |
| `eval_run_response.json` | `EvalRunResponse`（评测结果：对比表 + 逐题明细；由 `scripts/gen_eval_response_fixture.py` 真实跑出来，不做手工修饰） |
| `provider_models_request.json` | `ProviderModelsRequest`（`POST /ai/me/providers/models` 请求；`apiKey` 是**占位符**，不是任何人的密钥） |
| `provider_models_result.json` | `ProviderModelsResult`（模型清单：`models`/`truncated`/`source`；由 `scripts/gen_provider_models_fixture.py` 用**假供应商**走真实取数代码路径生成。⚠️ 响应里**没有**任何密钥字段，连掩码都没有） |
| `error_body.json` | `ErrorBody`（可展示错误码与提示） |

## 两侧怎么读同一组文件

- Python：`tests/test_schemas.py` 用 `pytest` 直接读本目录，校验「解析 → 序列化 → 与原文一致」。
- Java：`ai-service` 的契约测试（M0-4）按仓库相对路径读取同一批文件。因为 Python 侧的
  `uv sync` 只认 `stellar-ink-ai/pyproject.toml`，不能把 `tests/` 移到 Maven 模块内；
  统一用下面的相对路径常量，避免两边各存一份：

```text
# 从 stellar-ink-server/ai-service/ 出发
Path.of("..", "..", "stellar-ink-ai", "tests", "fixtures")
```

## 约定

- 键名一律**驼峰**（Java Jackson 默认输出；Python 用 `alias_generator=to_camel` 转换）。
- 字符串统一 UTF-8，中文直写不转义。
- 契约里**不允许**出现密钥、真实模型密钥、真实用户数据；示例内容全部是本仓库种子内容口径的假数据。
- 改契约要同时改：Python 模型、本目录 fixture、Java DTO（M0-4 起）与 `docs/api/README.md`。
