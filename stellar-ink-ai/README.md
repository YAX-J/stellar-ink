# 星笺 AI 编排服务（stellar-ink-ai）

Python AI 服务，**仅内网可达**：模型网关、RAG、Agent 与知识管道。浏览器不直连本服务，
对外协议、鉴权与 `Response<T>` 由 Java `ai-service :8107` 提供。

当前进度：**A / B / C / D 四个阶段与 E1、E2 已完成**（模型供应商层、切块与检索管道、Qdrant 适配、
评测台、问答与 SSE、Copilot、写作画像、只读 Agent；无密钥也能全绿）。
逐阶段核验证据与已知缺口见 [`../docs/ai/status.md`](../docs/ai/status.md)，项目整体进度见
[`../docs/status.md`](../docs/status.md)，实施顺序见 [`../docs/ai/fast-track-plan.md`](../docs/ai/fast-track-plan.md)（A→E 阶段），
每轮节奏见 [`../docs/ai/development-workflow.md`](../docs/ai/development-workflow.md)。

## 目录结构

```text
stellar-ink-ai/
├── pyproject.toml           依赖、ruff / mypy / pytest 配置（requires-python >= 3.11；本机实测 3.13）
├── app/
│   ├── main.py              应用工厂 + uvicorn 入口
│   ├── api/v1/              HTTP 路由（探活 / 问答与流式 / 写作建议 / 画像 / 评测 / Agent）
│   ├── core/                配置、日志、traceId、内部签名校验、密钥加解密
│   ├── providers/           模型供应商层（A1-3）：Chat/Embedding/Rerank + 按角色路由
│   ├── rag/                 切块 / 检索 / 重排 / 生成 / 评测 / 画像 / Agent / Qdrant 适配
│   └── schemas/             Pydantic 契约与共享 fixture（M0-2）
├── tests/                   单元与契约测试
└── scripts/                 离线索引、评测 CLI
```

> 目录刻意扁平：Qdrant 适配在 `rag/qdrant_store.py`、Agent 在 `rag/agent.py`，
> **没有** `vectorstore/`、`agents/`、`embedding/` 这类只有名字的空目录（曾存在过，已删）。

## 本地命令

```bash
cd stellar-ink-ai
uv sync --all-extras                  # 建 .venv 并装依赖（uv.lock 入库）
uv run ruff check . && uv run mypy app && uv run pytest   # 每轮最低验证门槛
uv run uvicorn app.main:app --host 127.0.0.1 --port 8200  # 起服务（仅回环）
curl -i http://127.0.0.1:8200/health                      # 期望 200 + X-Trace-Id
```

端口与拓扑见 [`../docs/ai/development-workflow.md`](../docs/ai/development-workflow.md) §4。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 本服务探活（服务名 / 版本 / 环境），**不含任何配置细节**；供 `ai-service` 探测 |

对外的 `/ai/**`（问答、写作建议、索引任务）由 Java `ai-service` 转发，落地前先写进
[`../docs/api/README.md`](../docs/api/README.md)。

## 跨语言契约

`app/schemas/` 是 Java 与 Python 之间唯一的数据结构来源，JSON 键统一驼峰：

| 契约 | 用途 |
|---|---|
| `QaStreamRequest` / `QaAnswer` | 星海问答（M3 检索、M5 SSE） |
| `WritingSuggestRequest` / `WritingSuggestResult` | 执笔页 Copilot 建议（M5） |
| `IndexRebuildRequest` / `IndexJob` | ADMIN 索引任务（M3/M4） |
| `Citation` / `Usage` / `ErrorBody` | 引用、用量与错误体 |

样例放 [`tests/fixtures/`](tests/fixtures/)，Java 契约测试（M0-4）与 Python Schema 测试
读**同一组 JSON**，路径约定见 [`tests/fixtures/README.md`](tests/fixtures/README.md)。

## 模型供应商层（app/providers）

业务代码只说「我需要 chat / embedding / rerank」，由配置决定背后是哪家：

| 模块 | 职责 |
|---|---|
| `base.py` | 三类接口（`ChatModel` / `EmbeddingModel` / `RerankModel`）—— 刻意不合成一个万能类 |
| `openai_compatible.py` | 一套 OpenAI 兼容实现，覆盖 DeepSeek / 硅基流动 bge-m3 / vLLM / SGLang |
| `fake.py` | 确定性假实现，`provider: fake` 启用：无密钥跑通链路，也是评测台的可复现基线 |
| `registry.py` | 角色 → 能力映射与实例缓存；能力不匹配时**取实例即报错** |
| `errors.py` | 分类错误（超时/限流/鉴权/上游不可用），区分「可重试」与「必须改配置」 |

- 配置来源有两档（见 `app/providers/config_source.py`）：`AI_PROVIDER_CONFIG_JSON` 环境变量优先，
  否则直连 MySQL 读 `ai_provider_config`（密钥列由 Java 侧 AES-GCM 加密、这里解密）。
  面板写的正是那张表；**本层不读 `user`/`post` 等业务表**。
- 换模型＝改配置：`base_url` + `model` + `apiKey`，业务代码与 Prompt 都不用动
- 密钥只出现在 `Authorization` 头里，不进日志；`ProviderConfig.fingerprint()` 刻意不含密钥，
  可安全用作缓存键与审计标识

## 安全边界

- 浏览器不得直接访问 Python 服务。
- 不解析 Sa-Token，不直接读写现有 `user`、`post` 等业务表；用户与角色只由 Java 经带时间戳的 HMAC 头传入。
- 不保存模型密钥：主密钥 `AI_SECRET_MASTER_KEY` 只从环境变量读取、无默认值，缺失时相关能力拒绝启动。
- 草稿与私密内容不出内网、不进公共索引；检索强制 `status=published`。
- 第一阶段不允许 AI 自动发布、修改或删除文章。

未列入当前里程碑的能力不在此目录自行扩展。
