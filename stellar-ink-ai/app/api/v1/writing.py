"""星笺 Copilot 接口（仅内网可达，由 Java `ai-service` 以 AUTHOR 门槛转发）。

红线：**只返回候选，不写正文**。作者在执笔页看到差异预览、点「采纳」之后才走
`/posts/**` 落库 —— 这条链路上没有任何自动写入。

错误口径：
- 草稿缺失/越界由契约层拦下（422）；
- 「模型没按格式回答」返回 **502 + `AI_UPSTREAM_UNAVAILABLE`**，与「这次没有建议」区分开。
"""

import logging
from functools import lru_cache

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.rag.writing import FakeCopilotChat, WritingCopilot, WritingSettings
from app.schemas.common import AiErrorCode
from app.schemas.writing import WritingSuggestRequest, WritingSuggestResult

logger = logging.getLogger(__name__)

router = APIRouter(tags=["writing"])


@lru_cache(maxsize=1)
def build_copilot() -> WritingCopilot:
    """装配 Copilot。

    当前用**按格式回答的离线桩**（`FakeCopilotChat`），不接 `FakeProvider` —— 后者只会回显提示词，
    会让解析层判定「格式不符」，于是所有润色/续写都变成 502，看起来像功能坏了。
    接上真实 Provider 后，这里换成面板配置的 chat 模型，并按配置指纹缓存装配。
    """
    return WritingCopilot(chat=FakeCopilotChat(), settings=WritingSettings())


@router.post("/writing/suggest", summary="写作建议（只给候选，不写正文）", response_model=None)
async def suggest(request: WritingSuggestRequest) -> WritingSuggestResult | JSONResponse:
    copilot = build_copilot()
    try:
        result = await copilot.suggest(request)
    except ValueError as error:
        # 模型没按格式回答属于上游问题：说清楚，别让前端以为「这次没有建议」
        logger.warning("copilot 解析失败：%s", error)
        return JSONResponse(
            status_code=502,
            content={
                "code": AiErrorCode.UPSTREAM_UNAVAILABLE.value,
                "message": f"写作建议生成失败：{error}",
            },
        )

    logger.info(
        "copilot suggested: task=%s candidates=%d model=%s latencyMs=%s",
        result.task.value,
        len(result.candidates),
        result.usage.model,
        result.usage.latency_ms,
    )
    return result
