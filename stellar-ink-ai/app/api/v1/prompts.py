"""提示词注册表的出口（M8）：`GET /prompts`。

只出**元数据**（名称/版本/变量/适用角色/评测状态/输出形状），不出模板全文 ——
模板可能很长，而观测出口的用途是「这次用的是哪一版、它被测过没有」。

**受内部签名保护**（不在公开白名单里）：它暴露的是系统的提示词结构，
对外没有用途；面板要看就走 ai-service 转发。
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter

from app.prompts import registry, registry_problems

router = APIRouter(tags=["prompts"])


@router.get("/prompts", summary="提示词清单与版本（M8）")
async def prompts() -> dict[str, Any]:
    """列出注册的提示词。

    ⚠️ 两个字段刻意如实：
    * `evaluation: null` = **这一版没有被评测过**（界面显示「尚未评测」）。
      编一个「效果良好」比留白危险得多 —— 后者只是信息少，前者会让人以为验过了。
    * `problems` = 启动自检发现的不一致（声明的变量与模板里用的对不上）。
      正常时是空数组；**非空时不要忽略** —— 那种提示词一渲染就会炸。
    """
    return {
        "prompts": registry().describe(),
        "problems": registry_problems(),
    }
