"""LLM Wiki 的契约（E4-1：带证据的主张抽取）。

键名一律驼峰（`ContractModel` 负责转换）。**响应里的统计与主张同等重要**：
只回一个主张列表的话，调用方看不到「模型提了 20 条、被校验挡掉 12 条」，
而那个比例正是判断「这套抽取能不能用」的关键。
"""

from typing import Annotated

from pydantic import Field

from app.schemas.base import ContractRequest, ContractResponse

MAX_CLAIMS_PER_REQUEST = 200


class WikiClaimsRequest(ContractRequest):
    """抽一轮主张。

    `maxPosts` 是**成本闸门**：每篇文章一次模型调用，全量抽取属于离线批处理，
    不该由一次 HTTP 请求决定（与 Agent 的预算同一条口径）。
    """

    max_posts: Annotated[
        int, Field(default=5, ge=1, le=50, description="最多抽几篇文章（每篇一次模型调用）")
    ] = 5

    max_claims_per_chunk: Annotated[
        int, Field(default=3, ge=1, le=10, description="每个段落最多接受几条主张")
    ] = 3


class WikiClaimView(ContractResponse):
    """一条带证据的主张：`quote` 是它能回到原文的凭据。"""

    text: Annotated[str, Field(min_length=1, max_length=200, description="原子主张")]
    post_id: Annotated[int, Field(ge=1, description="来源文章 ID")]
    chunk_index: Annotated[int, Field(ge=0, description="来源段落序号")]
    post_version: Annotated[str, Field(description="文章内容版本（文章改了，主张就该重建）")]
    content_hash: Annotated[str, Field(description="段落内容哈希（用于只失效受影响的那几条）")]
    quote: Annotated[str, Field(min_length=1, description="原文片段：**必须真的出现在该段落里**")]
    heading_path: Annotated[str, Field(default="", description="段落所属章节路径")]
    confidence: Annotated[float, Field(ge=0, le=1, description="模型自评的把握")]


class WikiExtractionStatsView(ContractResponse):
    """抽取账：提出的条数、留下的条数、按什么原因丢了多少。"""

    proposed: Annotated[int, Field(ge=0)]
    kept: Annotated[int, Field(ge=0)]
    dropped: Annotated[dict[str, int], Field(default_factory=dict, description="丢弃原因 → 条数")]
    posts: Annotated[int, Field(ge=0, description="实际抽了几篇文章")]
    entity_proposed: Annotated[int, Field(ge=0, description="模型提出的实体次数")]
    entity_kept: Annotated[int, Field(ge=0, description="通过证据校验的实体次数")]
    entities: Annotated[int, Field(ge=0, description="合并后的实体个数")]


class WikiEntityMentionView(ContractResponse):
    """一次实体出现：它挂在哪条主张上（这就是实体能回到证据的那条线）。"""

    name: Annotated[str, Field(min_length=1, max_length=40, description="原文里的写法")]
    post_id: Annotated[int, Field(ge=1)]
    chunk_index: Annotated[int, Field(ge=0)]
    claim_text: Annotated[str, Field(description="它出现在这条主张（或它的原文片段）里")]


class WikiEntityView(ContractResponse):
    """合并后的实体：一个规范化名字 + 它所有的出现。

    ⚠️ 合并只做**确定性归一化**（全角/半角、大小写、空白、首尾标点），不做语义合并 ——
    「星笺」与「STELLAR INK」是同一个东西，但错合的代价是一个说不清的知识条目，
    要等 roadmap §14 第 2 步说的 ADMIN 审核流。**那条线是后续切片。**
    """

    name: Annotated[str, Field(min_length=1, description="代表写法（出现最多的那种）")]
    normalized: Annotated[str, Field(min_length=1, description="归一化后的键")]
    kind: Annotated[str, Field(description="person/concept/tool/org/place/other")]
    count: Annotated[int, Field(ge=1, description="出现次数")]
    post_ids: Annotated[list[int], Field(default_factory=list, description="出自哪几篇文章")]
    mentions: Annotated[list[WikiEntityMentionView], Field(default_factory=list)]


class WikiClaimsResult(ContractResponse):
    claims: Annotated[
        list[WikiClaimView], Field(default_factory=list, max_length=MAX_CLAIMS_PER_REQUEST)
    ]
    entities: Annotated[
        list[WikiEntityView],
        Field(default_factory=list, description="合并后的实体（按出现次数排）"),
    ]
    stats: WikiExtractionStatsView
    notes: Annotated[list[str], Field(default_factory=list, description="给人看的提示")]
    usage_model: Annotated[str, Field(default="", description="实际使用的模型名")]
    latency_ms: Annotated[int, Field(ge=0)]
