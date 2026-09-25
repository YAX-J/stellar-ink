"""只读 Agent 接口（仅内网可达，由 Java `ai-service` 转发）。

这一层只做三件事：装配、跑、把 `AgentRun` 翻译成契约。
编排与预算全在 `app/rag/agent.py`（可单测），这里不重复任何判断。

当前用 `FakeProvider` 当模型：**它不是「假 Agent」**——循环、预算、引用核实、
中断这些逻辑全是真的，只有「模型怎么想下一步」这一步是桩。
Fake 的回显式回答解析不出决策 JSON，于是会走到「格式不符」分支并最终以
`doneReason=length` 收尾 —— 这正是离线环境下**如实**的表现，
比编一个看起来很聪明的答案有用（前端据此显示「预算内没收敛」）。
"""

import logging
from functools import lru_cache

from fastapi import APIRouter

from app.providers.fake import FakeProvider
from app.rag.agent import Agent, AgentRun, AgentSettings, ToolBox
from app.rag.agent_tools import read_only_tools
from app.rag.pipeline import RetrievalConfig, RetrievalPipeline, build_corpus
from app.rag.seed_corpus import load_seed_posts
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


@lru_cache(maxsize=1)
def build_agent() -> Agent:
    """装配只读 Agent 并缓存（语料切块与建索引只做一次）。"""
    corpus = build_corpus(load_seed_posts())
    if not corpus:
        raise ValueError("语料为空：Agent 没有可查的内容")
    pipeline = RetrievalPipeline(corpus=corpus, config=AGENT_RETRIEVAL, embedder=FakeProvider())
    # 作者身份与画像当前不属于 Agent 的可用上下文（真实形态由 Java 传作者 id 后再接）
    tools = read_only_tools(pipeline=pipeline)
    return Agent(chat=FakeProvider(), tools=ToolBox(tools), settings=AgentSettings())


@router.post("/agent/ask", summary="只读 Agent 问答（预算受限）", response_model=None)
async def ask(request: AgentAskRequest) -> AgentAskResult:
    agent = build_agent()
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
