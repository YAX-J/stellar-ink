# 星笺 AI 编排服务（预留）

本目录预留给 Python AI 编排服务。当前项目仍处于 AI 方案阶段，没有可运行实现，也未加入 Docker Compose。

## 计划职责

- 模型供应商适配与模型路由
- 文章索引、Embedding、Qdrant 检索与重排
- 带引用的 RAG 问答
- 写作建议、受控 Agent、记忆与 Wiki
- 评测、调用追踪和成本统计

## 计划结构

`app/` 保存应用代码，`tests/` 保存测试，`scripts/` 保存离线索引和评测脚本。目录中的子包只是边界占位，不代表功能已经完成。

## 安全边界

- 浏览器不得直接访问 Python 服务。
- Python 不解析 Sa-Token，不直接读写现有 `user`、`post` 等业务表。
- 业务身份、权限和对外协议由未来的 Java `ai-service` 负责。
- 第一阶段不允许 AI 自动发布、修改或删除文章。
- 草稿和私密内容不得默认发送到外部模型。

技术选型见 [`../docs/ai/README.md`](../docs/ai/README.md)，实施顺序见 [`../docs/ai/implementation-roadmap.md`](../docs/ai/implementation-roadmap.md)。没有明确的阶段任务时，不在此目录自行扩展代码。
