# 星笺 AI 编排服务（stellar-ink-ai）

Python AI 服务，**仅内网可达**：模型网关、RAG、Agent 与知识管道。浏览器不直连本服务，
对外协议、鉴权与 `Response<T>` 由 Java `ai-service :8107` 提供。

当前进度：**M0-1 工程骨架、M0-2 跨语言契约已完成**（可构建、可测试，全程 Fake Adapter，无任何密钥）。
实施顺序见 [`../docs/ai/implementation-roadmap.md`](../docs/ai/implementation-roadmap.md)，
每轮节奏见 [`../docs/ai/development-workflow.md`](../docs/ai/development-workflow.md)。

## 目录结构

```text
stellar-ink-ai/
├── pyproject.toml           依赖、ruff / mypy / pytest 配置（requires-python >= 3.11；本机实测 3.13）
├── app/
│   ├── main.py              应用工厂 + uvicorn 入口
│   ├── api/v1/              HTTP 路由（M0 只有探活）
│   ├── core/                配置、日志、traceId、错误模型
│   ├── providers/           模型供应商适配（M2）
│   ├── embedding/           Embedding 适配（M2/M3）
│   ├── rag/                 切块 / 检索 / 重排 / 生成（M3/M4）
│   ├── agents/              受控 Agent（M7）
│   ├── schemas/             Pydantic 契约与共享 fixture（M0-2）
│   └── vectorstore/         Qdrant 适配（M3）
├── tests/                   单元与契约测试
└── scripts/                 离线索引、评测 CLI
```

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

## 安全边界

- 浏览器不得直接访问 Python 服务。
- 不解析 Sa-Token，不直接读写现有 `user`、`post` 等业务表；用户与角色只由 Java 经带时间戳的 HMAC 头传入（M1）。
- 不保存模型密钥：密钥只从环境变量读取、无默认值，缺失时相关能力拒绝启动（M2 起）。
- 草稿与私密内容不出内网、不进公共索引；检索强制 `status=published`。
- 第一阶段不允许 AI 自动发布、修改或删除文章。

未列入当前里程碑的能力不在此目录自行扩展。
