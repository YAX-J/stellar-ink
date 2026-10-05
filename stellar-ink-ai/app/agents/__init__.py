"""Agent 司职（role/profile）包：把「Agent 是什么」从代码分支变成可枚举的数据。

- `profile.py`：`AgentProfile` 与「未知司职」异常
- `registry.py`：注册表（`AGENTS` / `DEFAULT_AGENT` / `get_profile` / `list_profiles`）
- `answerer.py`：无工具、纯生成
- `searcher.py`：今天 `/agent/ask` 的形态（ReAct 循环 + 只读工具）
- `verifier.py`：**只建骨架**，核验逻辑留给 A2

依赖方向是**单向的**：`app.agents.*` → `app.rag.agent`（要 `AgentSettings`），
反过来 `app/rag/agent.py` **不 import 本包** —— 循环依赖在这里不只是风格问题，
它会让「Agent 的可单测性」消失（import 期就要装配注册表）。
"""
