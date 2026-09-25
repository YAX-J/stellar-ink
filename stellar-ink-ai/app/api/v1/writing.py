"""星笺 Copilot 接口（仅内网可达，由 Java `ai-service` 以 AUTHOR 门槛转发）。

红线：**只返回候选，不写正文**。作者在执笔页看到差异预览、点「采纳」之后才走
`/posts/**` 落库 —— 这条链路上没有任何自动写入。

模型从哪来：面板里 `chat` 角色的配置（`ai_provider_config`）。**没有代码里的默认模型**，
未配置时返回 400 并说清去配哪个角色；唯一的例外是「面板里把协议显式选成 fake」——
那时用按格式回答的离线桩 `FakeCopilotChat`（见 `chat_for_writing`）。

错误口径：
- 草稿缺失/越界由契约层拦下（422）；
- 「模型没按格式回答」返回 **502 + `AI_UPSTREAM_UNAVAILABLE`**，与「这次没有建议」区分开；
- 「模型没配」返回 **400**，与上面的 502 区分开：一个是配置问题，一个是上游问题。
"""

import logging

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.api.v1.assembly import ASSEMBLY_ERRORS, assembly_error
from app.providers import runtime
from app.providers.base import ChatModel
from app.rag.writing import FakeCopilotChat, WritingCopilot, WritingSettings
from app.schemas.common import AiErrorCode
from app.schemas.writing import WritingSuggestRequest, WritingSuggestResult

logger = logging.getLogger(__name__)

router = APIRouter(tags=["writing"])


def chat_for_writing() -> ChatModel:
    """取写作建议要用的对话模型。

    为什么 fake 要换成 `FakeCopilotChat` 而不是直接用 `FakeProvider`：
    后者是通用回显桩，会把整段提示词吐回来，于是解析层判定「格式不符」，
    所有润色/续写都变成 502 —— 看起来像功能坏了，其实链路完全正常。
    这个替换**只发生在「显式选了 fake」这一条路上**，不是兜底：
    面板没配时这里会抛「角色 chat 尚未配置模型」，而不是悄悄用桩。
    """
    registry = runtime.registry()
    config = registry.config_of("chat")
    if config is not None and config.provider == "fake":
        return FakeCopilotChat()
    return registry.chat_model()


def build_copilot() -> WritingCopilot:
    """装配 Copilot。

    **不缓存**：真正贵的是检索管道与语料（在 `assembly` 里按配置指纹缓存），
    这里只是把模型实例套一层薄壳，按请求重建才能让「面板改了模型」立刻生效。
    """
    return WritingCopilot(chat=chat_for_writing(), settings=WritingSettings())


@router.post("/writing/suggest", summary="写作建议（只给候选，不写正文）", response_model=None)
async def suggest(request: WritingSuggestRequest) -> WritingSuggestResult | JSONResponse:
    try:
        copilot = build_copilot()
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)

    try:
        result = await copilot.suggest(request)
    except ValueError as error:
        # 模型没按格式回答属于上游问题：说清楚，别让前端以为「这次没有建议」。
        # 模型**调用失败**（超时/限流/未配）不走这里 —— 由 main.py 的 ProviderError
        # 全局处理统一转成 502/429/400，本层不必逐端点重复一遍。
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
