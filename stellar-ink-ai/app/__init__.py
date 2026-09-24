"""星笺 AI 编排服务（stellar-ink-ai）。

安全边界（见 docs/ai/development-workflow.md §7）：
- 仅内网可达，浏览器不直连；对外协议由 Java ``ai-service`` 负责。
- 不解析 Sa-Token，不读写 ``user`` / ``post`` 等业务表。
- 身份、角色与 traceId 只由 Java 经 HMAC 头传入（M1 实现）。
"""

__version__ = "0.1.0"
