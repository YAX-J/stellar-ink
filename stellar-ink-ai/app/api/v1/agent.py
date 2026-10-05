"""只读 Agent 接口（仅内网可达，由 Java `ai-service` 转发）。

A1 起这一层多了一件事：**按司职装配**。请求体里的 `agent` 决定用哪个 `AgentProfile`，
而司职决定了提示词、工具白名单与预算上限。骨架本身的三种司职在 `app/agents/`，
循环仍在 `app/rag/agent.py`（可单测）—— 这里不重复任何判断。

三条刻意保持的口径：

1. **未知司职给 422，不猜、不回退默认**。回退默认的后果是「拼错了名字照样跑完，
   账单与审计里记的是另一个岗位」，比报错难查得多。
2. **预算只能收紧**：司职声明它最多值多少（`profile.settings`），请求里的值取 min。
   司职自己声明的上限是**对外承诺**，调用方说不了算 —— 与 Java 侧 `bounded()` 同一条口径。
3. **工具是「装不进来」而不是运行期判断**：装配只取司职白名单里的工具，
   白名单里写了一个没实现的工具名会**直接报错**；而 `ToolBox` 依旧拒绝 `read_only=False`
   （红线 §7.4）。两道门叠加，写工具在任何一条路径上都进不来。

模型与检索从哪来：`app/api/v1/assembly.py`（面板配置是唯一来源，没有代码里的默认值）。
面板里把 chat 协议显式选成 `fake` 时，`FakeProvider` 的回显式回答解析不出决策 JSON，
于是会走到「格式不符」分支并最终以 `doneReason=length` 收尾 —— 这正是离线环境下
**如实**的表现，比编一个看起来很聪明的答案有用（前端据此显示「预算内没收敛」）。
"""

import logging

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.agents import answerer, registry, verifier
from app.agents.answerer import AnswerRun, Excerpt
from app.agents.profile import AgentProfile
from app.api.v1.assembly import (
    ASSEMBLY_ERRORS,
    CorpusError,
    assembly_error,
    pipeline_for,
    roles_for,
)
from app.core.internal_auth import InternalIdentity
from app.core.internal_auth_middleware import require_internal_identity
from app.providers import runtime
from app.rag import corpus as corpus_module
from app.rag.agent import Agent, AgentRun, AgentSettings, ToolBox, ToolSpec
from app.rag.agent_tools import chunk_index_of, read_only_tools, snippet_of
from app.rag.chunking import kind_label
from app.rag.eval_runner import RetrievalOutcome, RetrievedHit
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline
from app.rag.qa import DEFAULT_SNIPPET_LENGTH
from app.schemas.agent import (
    AgentAskRequest,
    AgentAskResult,
    AgentProfileList,
    AgentProfileView,
    AgentStepView,
    AgentVerifyProblemView,
    AgentVerifyRequest,
    AgentVerifyResult,
)
from app.schemas.common import AiErrorCode, Citation

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

#: 司职白名单里的工具名 —— **允许出现的那些**。
#: `read_only_tools` 是唯一的工具来源（MCP 用同一份），这里只做「挑哪几个」。
#: ⚠️ 白名单里出现本集合之外的名字 = 配置事故，装配时直接报错，**不静默少装**：
#: 「提示词里说能查 X、实际没装 X」会让模型反复调用一个不存在的工具，
#: 而现场看起来像「模型不行」。
PROFILE_TOOLS: frozenset[str] = frozenset({"search_posts"})

#: 请求点名时会**真的执行**的司职 —— 直接读注册表里的那份名单，**不在这里再写一份**。
#: 其余两种各有明确响应：`SKELETON_AGENTS`（`verifier`：计划内但本轮只有骨架）给 400 说明，
#: 未知名字给 422 —— 编一个答案会让「没接线」看起来像「能用了」，那是最贵的一种谎。
#: （口径与不变量见 `app/agents/registry.py`；`tests/test_agents_registry.py` 盯着它。）

#: 无工具司职的摘录段上限（与问答同一口径：引用是定位线索，不是全文复制）
ANSWERER_MAX_CITATIONS = 5


class ProfileNotWiredError(RuntimeError):
    """司职的声明与实现不一致（配置事故），应当以 500 暴露出来。

    两种情形：注册表里的司职声明了尚未实现的工具名；或缺省司职自己没接线。
    **刻意不是调用方能修的问题**，所以不当成 400 回给用户 ——
    它是「代码/注册表该改」，包成一句「请稍后再试」只会把方向带偏。
    与之相对，`UnknownAgentError`（名字拼错）与 EXECUTABLE_AGENTS 之外的名字
    （`verifier` 这种「计划内但还没做」）都是**调用方的正常遭遇**，各有明确响应。
    """


def build_agent(profile: AgentProfile, user_id: int | None = None) -> Agent:
    """按司职装配一个 Agent。

    **不缓存 Agent 本身**：它只是个薄壳，真正贵的检索管道由 `assembly` 按
    「语料版本 + 开关 + 配置指纹」缓存（整库嵌入因此只发生一次）。
    这样面板改了模型，下一次请求就用新的 —— 而不是要重启服务。

    也无所谓「共享状态」：每个请求都会新建一个（提示词、工具、预算都是不可变的），
    所以各司职之间不存在互相打扰的可能。
    """
    # 先一次性预检全部角色：Agent 每一步都要问模型，带着缺配置跑起来烧的是钱；
    # 也避免 pipeline_for 先抛「缺 embedding」，让用户以为配好 embedding 就没事了
    runtime.require_roles(*roles_for(AGENT_RETRIEVAL))
    # 生成用哪个模型可以由用户自己配（个人配置，M12）；检索那条链路始终取全局
    runtime.require_roles_for(user_id, "chat")
    # 作者身份与画像当前不属于 Agent 的可用上下文（真实形态由 Java 传作者 id 后再接）
    tools = _tools_for(profile)
    return Agent(
        chat=runtime.registry_for(user_id).chat_model(),
        tools=ToolBox(tools),
        settings=profile.settings,
        system_prompt=profile.system_prompt,
    )


def _tools_for(profile: AgentProfile) -> list[ToolSpec]:
    """按司职白名单装配工具：**只装它那组**。

    `read_only_tools()` 是唯一的工具来源。未知工具名在端点入口就已被挡下
    （见 `ask` 里的 `_unknown_tools` 预检），这里不再判一次 ——
    同一件事两处判断，迟早出现「一处改了另一处没改」。
    """
    available = read_only_tools(pipeline=pipeline_for(AGENT_RETRIEVAL))
    wanted = set(profile.tool_names)
    # 保持 `read_only_tools` 的原始顺序：工具在提示词里的顺序会影响模型先想到谁，
    # 而这个顺序是那份清单自己的属性，不该由司职声明里的书写顺序决定
    return [spec for spec in available if spec.name in wanted]


def _unknown_tools(profile: AgentProfile) -> list[str]:
    """司职声明了但代码里没实现的工具名（配置事故，不是调用方参数问题）。"""
    return [name for name in profile.tool_names if name not in PROFILE_TOOLS]


def build_answerer(profile: AgentProfile, user_id: int | None = None) -> answerer.Answerer:
    """装配无工具司职的生成器。

    **检索不在这一层**：它由调用方走同一条 `pipeline_for(AGENT_RETRIEVAL)`，
    所以「检索」不会出现第二份实现（生成层只接受已经编号好的摘录）。

    司职声明的 `settings` 必须真的传下去（`temperature` / `maxTokens` 就在其中）——
    偷懒用 `Answerer` 的默认值，等于「司职配了温度但没人读」，那种偏差看起来像模型不稳。
    """
    runtime.require_roles(*roles_for(AGENT_RETRIEVAL))
    runtime.require_roles_for(user_id, "chat")
    return answerer.Answerer(
        chat=runtime.registry_for(user_id).chat_model(), settings=profile.settings
    )


def _bounded(requested: int | None, ceiling: int) -> int:
    """预算取「请求值」与「司职上限」的更小值（照 Java `AiAgentController.bounded`）。

    只在请求值非空时夹取：空值表示「由服务端决定」，那时用司职声明的默认值。
    """
    if requested is None:
        return ceiling
    return min(requested, ceiling)


def _error(status_code: int, message: str) -> JSONResponse:
    """统一的错误体：`{code, message}`（与其它内网端点同一形状）。"""
    return JSONResponse(
        status_code=status_code,
        content={"code": AiErrorCode.BAD_REQUEST.value, "message": message},
    )


@router.post("/agent/ask", summary="只读 Agent 问答（预算受限）", response_model=None)
async def ask(
    request: AgentAskRequest,
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> AgentAskResult | JSONResponse:
    try:
        profile = registry.get_profile(request.agent)
    except registry.UnknownAgentError as error:
        # 未知司职是**调用方参数问题**，与「模型没配好」（400）不是一类：
        # 422 让前端能直接把它显示成「这个名字不存在」并列出可选值
        return _error(422, str(error))

    if profile.name not in registry.EXECUTABLE_AGENTS:
        if profile.name in registry.SKELETON_AGENTS:
            # 注册表里可见、但本轮没接线的岗位：以可读的 400 说明，
            # 而不是编一个答案（那会让「没接线」看起来像「能用了」）
            return _error(
                400,
                f"司职 {profile.name}（{profile.title}）本轮尚未接线，"
                f"当前可用：{'、'.join(sorted(registry.EXECUTABLE_AGENTS))}",
            )
        # 既没接线、也没被标成「只有骨架」= 接线遗漏（加了岗位忘了登记）。
        # 直接 500 暴露，**别让它悄悄退化**成生成路径 —— 那会让「忘了接线」表现得像「能用」
        raise ProfileNotWiredError(
            f"司职 {profile.name} 既不在可执行清单里，也没标成骨架：接线遗漏"
        )

    if profile.name in registry.MODEL_FREE_AGENTS:
        # 确定性司职（`verifier`）：**它不走 `/agent/ask`**。
        # 走到这里说明前端把核验员当成了「换个提示词的问答」——
        # 与其编一个答案，不如说清它该用哪条路径（核验是独立端点，输入是「答案 + 引用」）
        return _error(
            400,
            f"司职 {profile.name}（{profile.title}）做的是引用核验，不产出答案："
            "请用 POST /agent/verify（请求体 {answer, citations}）",
        )

    unknown_tools = _unknown_tools(profile)
    if unknown_tools:
        # 这是**代码/注册表的配置事故**，不是调用方能修的问题：照实说清楚是哪几个，
        # 而不是静默少装（那会让模型反复调用一个不存在的工具，现场像「模型不行」）
        raise ProfileNotWiredError(
            f"司职 {profile.name} 声明了尚未实现的工具：{'、'.join(unknown_tools)}"
        )

    try:
        if profile.tool_names:
            agent = build_agent(profile, identity.user_id)
        else:
            runner = build_answerer(profile, identity.user_id)
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)

    if not profile.tool_names:
        # 无工具司职：检索前置 + 一次生成（今天的 RAG 那条路的生成段）。
        # 检索在这里做而不是在 `answerer` 里：**检索只有一条编排**，
        # 生成层只接受已经编号好的摘录
        try:
            pipeline = pipeline_for(AGENT_RETRIEVAL)
        except ASSEMBLY_ERRORS as error:
            return assembly_error(error)
        retrieval = await pipeline.retrieve(request.question, top_k=ANSWERER_MAX_CITATIONS)
        result = await _answer_once(runner, profile, request.question, pipeline, retrieval)
        logger.info(
            "Agent 运行完成（生成路径）：agent=%s doneReason=%s citations=%d model=%s",
            profile.name,
            result.done_reason,
            len(result.citations),
            result.usage_model,
        )
        return result

    # 每次请求按自己的预算跑：settings 是不可变的，复制一份改而不是动缓存的装配。
    # 上限来自司职（`profile.settings`），请求只能收紧
    bounded = Agent(
        chat=agent.chat,
        tools=agent.tools,
        settings=AgentSettings(
            max_steps=_bounded(request.max_steps, profile.settings.max_steps),
            max_tool_calls=_bounded(request.max_tool_calls, profile.settings.max_tool_calls),
            max_observation_chars=profile.settings.max_observation_chars,
            tool_result_chars=profile.settings.tool_result_chars,
            temperature=profile.settings.temperature,
            max_tokens=profile.settings.max_tokens,
        ),
        system_prompt=profile.system_prompt,
    )
    step_run = await bounded.run(request.question)
    logger.info(
        "Agent 运行完成：agent=%s stopReason=%s steps=%d toolCalls=%d citations=%d model=%s",
        profile.name,
        step_run.stop_reason.value,
        len(step_run.steps),
        step_run.tool_calls,
        len(step_run.citations),
        step_run.usage_model,
    )
    return to_result(step_run, profile.name)


async def _answer_once(
    runner: answerer.Answerer,
    profile: AgentProfile,
    question: str,
    pipeline: RetrievalPipeline,
    outcome: RetrievalOutcome,
) -> AgentAskResult:
    """无工具司职的一次生成：摘录 → 引用 → 模型 → 契约。

    **引用只来自检索结果**（与问答同一条不变式）：模型输出不参与引用组装。
    """
    if outcome.refused or not outcome.hits:
        # 没有候选就是没有依据：**一次模型调用都不花**（省一次钱，
        # 也避免它对着空上下文编一个看起来像答案的东西）
        return _answer_result(
            answerer.refusal_run(int(outcome.latency_ms)), profile.name, [], 0
        )

    by_id: dict[str, IndexedChunk] = {chunk.chunk_id: chunk for chunk in pipeline.corpus}
    citations: list[Citation] = []
    excerpts: list[Excerpt] = []
    for hit in outcome.hits:
        citation = _citation_for(hit, by_id)
        if citation is None:
            # 命中回不到语料 = 索引与语料不是同一批：宁可报错也不要拼出假引用
            raise CorpusError(f"检索命中不在语料里：{hit.chunk_id}（需要重建索引）")
        citations.append(citation)
        excerpts.append(
            Excerpt(number=len(excerpts) + 1, title=citation.title, text=citation.snippet)
        )

    run = await runner.generate(profile.system_prompt, question, excerpts)
    return _answer_result(run, profile.name, citations, int(outcome.latency_ms))


def _citation_for(hit: RetrievedHit, by_id: dict[str, IndexedChunk]) -> Citation | None:
    """把一条命中翻成契约引用（片段取原文，长度与问答同一口径）。

    片段与段落序号直接复用 `agent_tools` 里那份：**同一份内容在两处裁出不同长度/不同的
    段落号**，读者点回原文就会落到错的地方 —— 那是「引用看起来对、实际指错」的经典形态。
    """
    chunk = by_id.get(hit.chunk_id)
    if chunk is None:
        return None
    return Citation(
        kind=chunk.kind,
        post_id=chunk.post_id,
        title=chunk.title or f"{kind_label(chunk.kind)} {chunk.post_id}",
        chunk_index=chunk_index_of(chunk),
        snippet=snippet_of(chunk, DEFAULT_SNIPPET_LENGTH),
        score=float(hit.score),
    )


def _answer_result(
    run: AnswerRun, agent: str, citations: list[Citation], retrieval_ms: int
) -> AgentAskResult:
    """无工具司职的 `AnswerRun` → 契约。

    `steps` 为空、`toolCalls=0`：**这个司职没有工具**，编一条「查了什么」的记录
    等于让前端把一次纯生成显示成多步检索。耗时是「检索 + 生成」的总时长 ——
    只报生成那段会让用户看到的数字比实际等待短。
    """
    return AgentAskResult(
        agent=agent,
        answer=run.answer,
        citations=citations,
        done_reason=run.done_reason.value,
        steps=[],
        tool_calls=0,
        interrupted_by="",
        usage_model=run.usage_model,
        latency_ms=retrieval_ms + run.latency_ms,
    )


@router.get("/agent/profiles", summary="可用的 Agent 司职清单", response_model=None)
async def profiles(
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> AgentProfileList:
    """给前端取清单用（`list_profiles()` 的出口）。

    只出**元数据**：名字、中文名、工具名、预算、场景。提示词不在其中 ——
    它的长度不适合放在一个「选择器」的响应里，要看提示词请走 `/prompts`（M8 注册表）。
    """
    del identity  # 身份只用来过签名校验（清单本身对所有登录用户相同）
    return AgentProfileList(
        default_agent=registry.DEFAULT_AGENT,
        agents=[
            AgentProfileView(
                name=profile.name,
                title=profile.title,
                tool_names=list(profile.tool_names),
                scene=profile.scene,
                max_steps=profile.settings.max_steps,
                max_tool_calls=profile.settings.max_tool_calls,
            )
            for profile in registry.list_profiles()
        ],
    )


@router.post("/agent/verify", summary="确定性引用核验（零模型调用）", response_model=None)
async def verify_citations(
    request: AgentVerifyRequest,
    identity: InternalIdentity = Depends(require_internal_identity),  # noqa: B008 - 见 app/main.py
) -> AgentVerifyResult | JSONResponse:
    """核对「答案 + 引用」：编号越界 / 未标编号 / 片段与原文对不上。

    **一个模型都不调**（这是它能在界面上随手点、也能进单测的原因）：
    这三条判断全是确定性的，模型只会给它加不确定性。
    判定本身在 `app/agents/verifier.py`（可单测），这里只做装配与翻译。
    """
    del identity  # 身份只用来过签名校验（核验对所有登录用户一视同仁）
    chunks = _oracle_chunks()
    report = verifier.verify(
        request.answer,
        request.citations,
        index=verifier.chunk_index_of(chunks),
    )
    logger.info(
        "引用核验完成：verdict=%s checked=%d citations=%d problems=%d uncited=%s outOfRange=%d",
        report.verdict,
        report.checked,
        len(request.citations),
        len(report.problems),
        report.uncited,
        len(report.out_of_range),
    )
    return to_verify_result(report)


def _oracle_chunks() -> list[IndexedChunk]:
    """核验用的**原文**：直接读语料，不走检索管道。

    ⚠️ 这里刻意不用 `pipeline_for()`：那会把整库嵌入一遍（那是钱），
    而核验只需要「原文」这一侧。`cached_corpus()` 与检索**同源**
    （同一份语料、同一套切块），但**不碰任何模型角色** ——
    核验因此不会被「面板还没配 embedding」卡住（它本来也不需要）。

    语料为空时抛 `CorpusError`（端点翻成 400）：空语料不会报错，
    只会让核验「什么都比不了」，而那个结果看起来像「引用都没问题」。
    """
    chunks = corpus_module.cached_corpus()
    if not chunks:
        raise CorpusError("语料为空：核验没有可比对的原文")
    return chunks


def to_verify_result(report: verifier.VerificationReport) -> AgentVerifyResult:
    """`VerificationReport` → 契约。**只做字段搬运**（判断在 `app/agents/verifier.py`）。"""
    return AgentVerifyResult(
        verdict=report.verdict,
        checked=report.checked,
        evidence_available=report.evidence_available,
        cited_indexes=list(report.cited_indexes),
        out_of_range=list(report.out_of_range),
        uncited=report.uncited,
        problems=[
            AgentVerifyProblemView(kind=item.kind, message=item.message)
            for item in report.problems
        ],
    )


def to_result(run: AgentRun, agent: str = registry.DEFAULT_AGENT) -> AgentAskResult:
    """`AgentRun` → 契约。**只做字段搬运**，不做任何判断（判断在编排层）。"""
    return AgentAskResult(
        agent=agent,
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
