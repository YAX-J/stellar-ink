# Java AI 客户端（预留）

本目录预留给 Java 服务调用 Python AI 编排服务的客户端契约。当前没有实现，也未加入 [`../pom.xml`](../pom.xml) 的 Maven reactor。

## 未来职责

- 定义 Java → Python 的内部请求与响应 DTO。
- 封装 HTTP/流式客户端、超时、取消和降级策略。
- 透传 traceId，并对内部请求进行 HMAC 签名。
- 隔离具体 Python API，使 Java 业务层不依赖供应商 SDK。

## 不负责

- 不承载对浏览器开放的 Controller。
- 不实现 Sa-Token 登录与角色判断。
- 不直接访问 MySQL、Qdrant 或 Redis。
- 不保存模型密钥，不包含模型供应商业务逻辑。
- 不作为 content-service 的普通内部调用契约。

正式建设前按 [`../../docs/ai/implementation-roadmap.md`](../../docs/ai/implementation-roadmap.md) 的 M0 阶段创建 POM、契约测试与 Fake Adapter；未进入对应任务前保持预留状态。
