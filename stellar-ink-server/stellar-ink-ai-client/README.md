# Java AI 客户端（stellar-ink-ai-client）

Java → Python AI 服务的**内部客户端契约**库：Feign 接口、内部 DTO、签名头常量与 Fallback。
已登记进 [`../pom.xml`](../pom.xml) 的 Maven reactor，由未来的 `ai-service` 依赖。

当前进度：**M0-3 已完成**（契约与降级语义冻结，尚未被任何服务装配调用）。
实施顺序见 [`../../docs/ai/implementation-roadmap.md`](../../docs/ai/implementation-roadmap.md)。

## 职责

- 定义 Java → Python 的内部请求与响应 DTO（`com.stellarink.aiclient.dto`）。
- 冻结内部接口路径与 HMAC 签名头常量（`constant/`）；签名计算在 M1 实现。
- 封装降级：Python 不可用时**明确报 503**，不返回空结果（`fallback/`）。
- 隔离具体 Python API，使 Java 业务层不依赖模型供应商 SDK。

## 不负责

- 不承载对浏览器开放的 Controller（那是 `ai-service` 的职责）。
- 不实现 Sa-Token 登录与角色判断，不直接访问 MySQL、Qdrant 或 Redis。
- 不保存模型密钥：模型 Key 只属于 Python 服务；本模块仅用 `AI_INTERNAL_SECRET` 做内网签名（M1）。
- 不作为 content-service 的普通内部调用契约。

## 目录

```text
src/main/java/com/stellarink/aiclient/
├── client/PythonAiClient.java           Feign 契约（/health、/qa/stream、/writing/suggest、/admin/**）
├── constant/AiContractPaths.java        Python 侧路径常量
├── constant/AiInternalHeaders.java      X-AI-* 签名头与时间窗常量
├── dto/                                 内部 DTO（与 Python Pydantic 契约一一对应）
├── enums/                               与 Python 取值逐字一致的枚举（@JsonValue 小写字面量）
└── fallback/PythonAiClientFallbackFactory.java   降级：抛 503 业务异常
```

## 契约测试

[`src/test/java/.../contract/AiContractTest.java`](src/test/java/com/stellarink/aiclient/contract/AiContractTest.java)
读取 `stellar-ink-ai/tests/fixtures/` 的**同一组** JSON（路径约定见
[`../../stellar-ink-ai/tests/fixtures/README.md`](../../stellar-ink-ai/tests/fixtures/README.md)），
校验「解析 → 序列化 → 与原文一致」，Python 侧有对称的 `tests/test_schemas.py`。

```bash
cd stellar-ink-server
mvn -pl stellar-ink-ai-client -am test
```

比较前会把时间字段归一到 UTC 瞬时（Python 输出 `+08:00`、Java `Instant` 固定写 `Z`，
同一时刻字面量不同），并把无类型整数按 Long 读，避免「JSON 数字宽度」造成假红灯。

## 边界提醒

浏览器不直连 Python（8200 仅内网）；对外接口是 `ai-service` 的 `/ai/**`。
改契约要同时改：Python 模型、共享 fixture、本模块 DTO 与 `docs/api/README.md`。
