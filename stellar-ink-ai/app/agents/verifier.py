"""`verifier` 司职：**确定性引用核验**（A2；本轮一个模型都不调）。

## 为什么先做这一半

核验的价值在于**能判对错**。而「引用有没有真的支持答案」里有一半是确定性判断，
根本不需要模型：

1. **编号越界**：答案里写了 `[5]`，可这次只检索到 3 条 —— 这条引用指向不存在的东西；
2. **一个编号都没标**：模型漏标时前端会把它当成「这次没有依据」，而其实检索到过
   （`Agent` 的循环里甚至有「模型一条都没标对就退化成带上观察到的引用」这条兜底）；
3. **片段与原文对不上**：每条 citation 的片段是否真的出现在它标注的那段原文里
   —— 检索侧有 chunk 文本，所以这是可以**查**的，不必信模型。

这三条零模型调用、可单测、能立刻反馈到界面。**语义核验**（用模型逐条判断论断是否有依据）
留给下一轮：它要依赖这一层的结论打底（连片段都找不到的引用，先别谈「论断成不成立」），
也需要一套新的输出契约，硬塞进本轮只会让两边都变形。

## 复用而不是新写（口径只有一份）

- 引用编号的解析：`app.rag.agent.parse_citation_marks`（问答与 Agent 的提示词都要求
  「用 [1] [2] 标注来源」，解析必须只有一处）；
- 引用的文档标识：`app.rag.agent.citation_key`（`kind + postId + chunkIndex`）
  —— 与 `verified_citations`（「引用必须被观察到」）用的是同一把钥匙；
- 「片段有没有被观察到」：`app.rag.evidence.evidence_contains` / `chunk_text`，
  与 LLM Wiki 的 `quote` 校验同一套规范化。

`verify()` 因此只是**把这些既有判断串起来并翻译成人话**，不引入新的比对规则。

## 一条刻意的口径

`ok` 只表示「**没查出问题**」，不等于「这段答案是对的」：
语义层面的「论断有没有依据」本轮不判。报告里因此带 `checked`（实际比对过几条片段），
前端显示「已核对 N 条引用」而不是「答案已核实」——**界面不得承诺服务端没算过的东西**。
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field

from app.agents.profile import AgentProfile
from app.rag.agent import AgentSettings, citation_key, parse_citation_marks
from app.rag.evidence import MIN_EVIDENCE_CHARS, chunk_text, evidence_contains
from app.rag.pipeline import IndexedChunk
from app.schemas.common import Citation

#: 问题分类（写进契约，前端与排障都按这些键读；**不要用中文当键**）
PROBLEM_OUT_OF_RANGE = "outOfRange"
PROBLEM_UNCITED = "uncited"
PROBLEM_SNIPPET_NOT_FOUND = "snippetNotFound"
PROBLEM_UNKNOWN_CHUNK = "unknownChunk"

#: `verdict` 的取值。只有两种：查出问题（`warn`）/ 没查出问题（`ok`）。
#: **没有 `error`**：核验本身失败（语料取不到之类）是 HTTP 层的事，
#: 混进这个字段会让「核验器坏了」看起来像「引用有问题」——那是两件要动手改的事。
VERDICT_OK = "ok"
VERDICT_WARN = "warn"


#: 段落索引键：与 `app.rag.agent.citation_key` 完全一致（文章 3 与笔记 3 是两个文档）
ChunkKey = tuple[str, int, int]


@dataclass(frozen=True, slots=True)
class VerifyProblem:
    """一条可读的问题。`kind` 给程序，`message` 给人。"""

    kind: str
    message: str


@dataclass(frozen=True, slots=True)
class VerificationReport:
    """一次核验的结果。

    - `verdict`：`ok`（没查出问题）/ `warn`（查出问题）；
    - `checked`：**回查到原文**的引用条数。它是 `ok` 的可信度分母 ——
      「一条都没比过」与「比了三条都没问题」都是 `ok`，但含义完全不同；
    - `evidence_available`：这次**有没有原文可查**（`index is not None`）。
      它为 False 时 `checked` 必然是 0，前端要说「没能核对原文」而不是「引用没问题」；
    - `cited_indexes`：答案里标出的编号（按出现顺序去重）；
    - `out_of_range`：其中越界的那些；
    - `uncited`：有引用却一个编号都没标。
    """

    verdict: str
    checked: int = 0
    evidence_available: bool = False
    cited_indexes: list[int] = field(default_factory=list)
    out_of_range: list[int] = field(default_factory=list)
    uncited: bool = False
    problems: list[VerifyProblem] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """没查出问题。**不等于「答案是对的」**（语义核验还没做，见模块 docstring）。"""
        return self.verdict == VERDICT_OK


def chunk_index_of(chunks: Sequence[IndexedChunk]) -> dict[ChunkKey, IndexedChunk]:
    """按**文档标识**建段落索引：核验回查原文时用的就是这把钥匙。

    键与 `app.rag.agent.citation_key` 完全一致（文章 3 与笔记 3 是两个文档）——
    两处各拼一次键，就会出现「Agent 认为这条引用存在、核验器认为查不到原文」。
    """
    return {citation_key(citation_of_chunk(chunk)): chunk for chunk in chunks}


def verify(
    answer: str,
    citations: Sequence[Citation],
    *,
    observed: Sequence[Citation] | None = None,
    index: dict[ChunkKey, IndexedChunk] | None = None,
) -> VerificationReport:
    """确定性核验：编号越界 / 未标编号 / 片段与原文对不上。

    - `citations`：**待核验的引用**（就是答案后面附的那份清单）；
    - `observed`：这次实际检索到并交给模型的引用；留空时取 `citations` 本身
      （`/agent/ask` 的响应里，引用**就是**观察到的那些 —— 它们由工具结果贴回来，
      不是模型写的）。区分这两个入参是为了让「模型声称的引用」与「真的观察到的引用」
      在签名上分开：语义核验（下一轮）里前者来自模型输出，后者来自检索结果。
      它同时决定**编号的合法上界**；
    - `index`：段落索引（`chunk_index_of(语料)`），用来回查每条引用的原文。
      **不给就不做「片段与原文对不上」这一条**，并且如实把
      `evidence_available` 标成 False、`checked` 为 0 —— 「这次没得比」必须说出来，
      否则界面会把 `ok + checked=0` 显示成「引用没问题」（那是最糟的一种误导）。
    """
    claims = list(citations)
    seen = list(observed) if observed is not None else list(citations)
    marks = parse_citation_marks(answer)
    problems: list[VerifyProblem] = []

    total = len(seen)
    out_of_range = [number for number in marks if number < 1 or number > total]
    if out_of_range:
        # 越界的编号指向不存在的引用：前端点不动，读者也核不了
        listed = "、".join(f"[{number}]" for number in out_of_range)
        problems.append(
            VerifyProblem(
                kind=PROBLEM_OUT_OF_RANGE,
                message=(
                    f"答案标了 {listed}，但这次只有 {total} 条引用（编号要在 "
                    f"1..{total} 之间）—— 越界的编号指不到任何东西。"
                ),
            )
        )

    uncited = bool(seen) and not marks
    if uncited:
        # 「没标编号」与「没有依据」是两件事：检索到过，只是模型没说清哪句来自哪条
        problems.append(
            VerifyProblem(
                kind=PROBLEM_UNCITED,
                message=(
                    f"答案一条引用编号都没标，但这次检索到 {total} 条引用 —— "
                    "读者无法核对哪句话来自哪一段（这不等于「没有依据」）。"
                ),
            )
        )

    checked = 0
    # 没有原文可查时**一条都不比对**：`evidence_available` 会如实说出来，前端据此说
    # 「没能核对原文」而不是「引用没问题」——**「没查」与「查了没问题」是两件事**。
    # （把 None 收敛成空索引而不是在循环里判空：判定只有一处，循环体不必每次想一遍）
    evidence_available = index is not None
    lookup: dict[ChunkKey, IndexedChunk] = index or {}
    for position, citation in enumerate(claims, start=1):
        chunk = lookup.get(citation_key(citation))
        if chunk is None:
            if not evidence_available:
                # 压根没有原文这一侧：不是「这条引用有问题」，而是「这次没得比」
                continue
            # 引用指向的段落不在当前语料里：可能是文章改过/下架，也可能索引与语料不是同一批。
            # **刻意不猜是哪一种** —— 两种都只能如实说「回不到原文」
            problems.append(
                VerifyProblem(
                    kind=PROBLEM_UNKNOWN_CHUNK,
                    message=(
                        f"第 {position} 条引用（{citation.kind}:{citation.post_id}"
                        f" 第 {citation.chunk_index} 段）在当前语料里找不到对应原文 —— "
                        "文章可能在检索之后被改过，或索引需要重建。"
                    ),
                )
            )
            continue

        checked += 1
        source = chunk_text(chunk)
        if not evidence_contains(citation.snippet, source):
            problems.append(
                VerifyProblem(
                    kind=PROBLEM_SNIPPET_NOT_FOUND,
                    message=(
                        f"第 {position} 条引用的片段在它标注的原文里找不到"
                        f"（{citation.kind}:{citation.post_id} 第 {citation.chunk_index} 段，"
                        f"片段至少 {MIN_EVIDENCE_CHARS} 字才算数）—— 这条引用没有被观察到。"
                    ),
                )
            )

    return VerificationReport(
        verdict=VERDICT_OK if not problems else VERDICT_WARN,
        checked=checked,
        evidence_available=evidence_available,
        cited_indexes=marks,
        out_of_range=out_of_range,
        uncited=uncited,
        problems=problems,
    )


def citation_of_chunk(chunk: IndexedChunk) -> Citation:
    """把一块语料翻成契约引用（只用来**建索引键**，所以片段取原文而不是检索用文本）。

    ⚠️ 片段必须是 `chunk_text`（原文）而不是 `IndexedChunk.text`（检索用文本，含标题）：
    后者会让「引用标题」也算「回到原文」。
    """
    text = chunk_text(chunk)
    return Citation(
        kind=chunk.kind if chunk.kind in {"post", "note"} else "post",
        post_id=chunk.post_id,
        title=chunk.title or f"{chunk.kind} {chunk.post_id}",
        chunk_index=_chunk_index(chunk),
        # `Citation.snippet` 要求非空：拿不到原文时退回检索文本，
        # 那至少是这一段的内容（而不是编一个片段出来）
        snippet=text or chunk.text or "(空段落)",
    )


def _chunk_index(chunk: IndexedChunk) -> int:
    raw = chunk.payload.get("chunkIndex")
    return raw if isinstance(raw, int) and raw >= 0 else 0


#: 核验提示词的**占位**：本轮不调模型，所以它还没有承载任何输出协议。
#: 留着是因为司职要在注册表里可见（前端能列出「核验员」这个岗位），
#: 而 A3 的语义核验会在这里写逐条判定的协议 —— **那时才写**，现在编一个等于给下一轮挖坑。
SYSTEM_PROMPT = (
    "你是「星笺」的核验员。你的职责是核对一段答案里的每条主张能否回到给定证据，"
    "并指出站不住的那几条。**你不负责检索，也不负责重新作答**。"
)

#: 预算：核验是**确定性判断**，一次模型调用都不需要。
#: `max_steps=1` / `max_tool_calls=1` 只是 `AgentSettings` 的下界（它不接受 0）；
#: 本司职的 `tool_names` 是空的，这两个数字在任何路径上都不会被读到。
SETTINGS = AgentSettings(
    max_steps=1,
    max_tool_calls=1,
    max_observation_chars=4_000,
)

PROFILE = AgentProfile(
    name="verifier",
    title="核验员",
    system_prompt=SYSTEM_PROMPT,
    tool_names=(),
    settings=SETTINGS,
    scene="agent",
)
