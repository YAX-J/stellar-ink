"""生成跨语言共享的评测**响应**样例（`tests/fixtures/eval_run_response.json`）。

为什么需要它：`eval_run_request.json` 只覆盖请求方向。响应方向（对比表 + 逐题明细）
是前端要渲染、Java 要反序列化的那一半，没有样例就只能靠「跑一次看看」——
而契约漂移最常发生在这种没人校验的地方。

样例必须是**真实跑出来的**：这里用 `max_cases=4` 跑一轮（指标与逐题明细一一对应、
互相自洽），不做任何手工修饰。谁改了响应字段，两侧的契约测试会同时红。

用法：``uv run python scripts/gen_eval_response_fixture.py``
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

from app.rag.eval_service import run_evaluation, to_response
from app.schemas.eval import EvalRunRequest

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console

FIXTURE = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "eval_run_response.json"


async def main() -> None:
    outcome = await run_evaluation(EvalRunRequest(max_cases=4))
    response = to_response(outcome)
    payload = response.model_dump(by_alias=True, exclude_none=False, mode="json")
    FIXTURE.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"已写入 {FIXTURE}（{len(payload['cases'])} 条逐题明细，"
        f"{len(payload['strategies'])} 组策略）"
    )


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    asyncio.run(main())
