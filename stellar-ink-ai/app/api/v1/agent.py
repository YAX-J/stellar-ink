"""只读 Agent 接口（仅内网可达，由 Java `ai-service` 转发）。

这一层只做三件事：装配、跑、把 `AgentRun` 翻译成契约。
编排与预算全在 `app/rag/agent.py`（可单测），这里不重复任何判断。

模型与检索从哪来：`app/api/v1/assembly.py`（面板配置是唯一来源，没有代码里的默认值）。
面板里把 chat 协议显式选成 `fake` 时，`FakeProvider` 的回显式回答解析不出决策 JSON，
于是会走到「格式不符」分支并最终以 `doneReason=length` 收尾 —— 这正是离线环境下
**如实**的表现，比编一个看起来很聪明的答案有用（前端据此显示「预算内没收敛」）。
"""

import logging

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.api.v1.assembly import ASSEMBLY_ERRORS, assembly_error, pipeline_for, roles_for
from app.core.internal_auth import InternalIdentity
from app.core.internal_auth_middleware import require_internal_identity
from app.providers import runtime
from app.rag.agent import Agent, AgentRun, AgentSettings, ToolBox
from app.rag.agent_tools import read_only_tools
from app.rag.pipeline import RetrievalConfig
from app.schemas.agent import AgentAskRequest, AgentAskResult, AgentStepView

logger = logging.getLogger(__name__)

router = APIRouter(tags=["agent"])

#: Agent 用的检索配置：与问答同一条管线、同一份口径（**不做第二套检索**）
AGENT_RETRIEVAL = RetrievalConfig(
    enable_sparse=True,
    enable_dense=True,
    candidate_k=20,
    min_dense_score=0.0,
    label="agent",
)


def build_agent(user_id: int | None = None) -> Agent:
    """装配只读 Agent。

    **不缓存 Agent 本身**：它只是个薄壳，真正贵的检索管道由 `assembly` 按
    「语料版本 + 开关 + 配置指纹」缓存（整库嵌入因此只发生一次）。
    这样面板改了模型，下一次请求就用新的 —— 而不是要重启服务。
    """
    # 先一次性预检全部角色：Agent 每一步都要问模型，带着缺配置跑起来烧的是钱；
    # 也避免 pipeline_for 先抛「缺 embedding」，让用户以为配好 embedding 就没事了
    runtime.require_roles(*roles_for(AGENT_RETRIEVAL))
    # 生成用哪个模型可以由用户自己配（个人配置，M12）；检索那条链路始终取全局
    runtime.require_roles_for(user_id, "chat")
    # 作者身份与画像当前不属于 Agent 的可用上下文（真实形态由 Java 传作者 id 后再接）
    tools = read_only_tools(pipeline=pipeline_for(AGENT_RETRIEVAL))
    return Agent(
        chat=runtime.registry_for(user_id).chat_model(),
        tools=ToolBox(tools),
        settings=AgentSettings(),
    )


@router.post("/agent/ask", summary="只读 Agent 问答（预算受限）", response_model=None)
async def ask(
    request: AgentAskRequest,
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> AgentAskResult | JSONResponse:
    try:
        agent = build_agent(identity.user_id)
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)

    # 每次请求按自己的预算跑：settings 是不可变的，复制一份改而不是动缓存的装配
    bounded = Agent(
        chat=agent.chat,
        tools=agent.tools,
        settings=AgentSettings(
            max_steps=request.max_steps,
            max_tool_calls=request.max_tool_calls,
        ),
    )
    run = await bounded.run(request.question)
    logger.info(
        "Agent 运行完成：stopReason=%s steps=%d toolCalls=%d citations=%d model=%s",
        run.stop_reason.value,
        len(run.steps),
        run.tool_calls,
        len(run.citations),
        run.usage_model,
    )
    return to_result(run)


def to_result(run: AgentRun) -> AgentAskResult:
    """`AgentRun` → 契约。**只做字段搬运**，不做任何判断（判断在编排层）。"""
    return AgentAskResult(
        answer=run.answer,
        citations=run.citations,
        done_reason=run.stop_reason.value,
        steps=[
            AgentStepView(
                index=step.index,
                thought=step.thought,
                tool=step.tool,
                label=step.label,
                error=step.error,
            )
            for step in run.steps
        ],
        tool_calls=run.tool_calls,
        interrupted_by=run.interrupted_by,
        usage_model=run.usage_model,
        latency_ms=run.latency_ms,
    )
