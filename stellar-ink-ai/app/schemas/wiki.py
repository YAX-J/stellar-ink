"""LLM Wiki 的契约（E4-1：带证据的主张抽取）。

键名一律驼峰（`ContractModel` 负责转换）。**响应里的统计与主张同等重要**：
只回一个主张列表的话，调用方看不到「模型提了 20 条、被校验挡掉 12 条」，
而那个比例正是判断「这套抽取能不能用」的关键。
"""

from typing import Annotated

from pydantic import Field

from app.schemas.base import ContractRequest, ContractResponse

MAX_CLAIMS_PER_REQUEST = 200
#: 失效盘点一次最多带多少条锚点（多了会让请求体变成几 MB，而盘点是同步接口）
MAX_STALE_CLAIMS_PER_REQUEST = 5000


class WikiClaimsRequest(ContractRequest):
    """抽一轮主张。

    `maxPosts` 是**成本闸门**：每篇文章一次模型调用，全量抽取属于离线批处理，
    不该由一次 HTTP 请求决定（与 Agent 的预算同一条口径）。

    `postIds` 给「**定向重建**」用（E4-11）：只抽这几篇。它与 `maxPosts` 是
    **两个不同的意图**（「按顺序取几篇」vs「就要这几篇」），同时传时以 `postIds` 为准、
    `maxPosts` 不再截断 —— 否则会出现「报告说 3 篇要重建，实际重建的是头 5 篇里的 1 篇」。
    """

    max_posts: Annotated[
        int, Field(default=5, ge=1, le=50, description="最多抽几篇文章（每篇一次模型调用）")
    ] = 5

    max_claims_per_chunk: Annotated[
        int, Field(default=3, ge=1, le=10, description="每个段落最多接受几条主张")
    ] = 3

    post_ids: Annotated[
        list[int],
        Field(
            default_factory=list,
            max_length=50,
            description="定向重建：只抽这几篇（为空则按顺序取 maxPosts 篇）",
        ),
    ] = Field(default_factory=list)


class WikiStaleClaimView(ContractRequest):
    """库里存的一条主张锚点（判定失效只需要这三个字段）。"""

    post_id: Annotated[int, Field(ge=1)]
    chunk_index: Annotated[int, Field(ge=0)]
    content_hash: Annotated[str, Field(default="", max_length=64, description="抽取时的段落哈希")]


class WikiStaleRequest(ContractRequest):
    """失效盘点的输入：把库里的锚点交给 Python（**只有它知道当前切块结果**）。"""

    claims: Annotated[
        list[WikiStaleClaimView],
        Field(default_factory=list, max_length=MAX_STALE_CLAIMS_PER_REQUEST),
    ]


class WikiStaleResult(ContractResponse):
    """失效盘点（E4-11）：三种状态**分开**报，因为处置不一样（重建 / 清理 / 不用动）。"""

    checked: Annotated[int, Field(ge=0, description="查过多少条主张")]
    current: Annotated[int, Field(ge=0, description="锚点仍对得上，不用动")]
    stale: Annotated[int, Field(ge=0, description="段落内容变了（文章改过）")]
    orphan: Annotated[int, Field(ge=0, description="段落已不存在（文章删了或删短了）")]
    stale_post_ids: Annotated[list[int], Field(default_factory=list, description="需要重建的文章")]
    orphan_post_ids: Annotated[
        list[int], Field(default_factory=list, description="有失效引用的文章（需要清理）")
    ]
    notes: Annotated[list[str], Field(default_factory=list, description="给人看的解释")]


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
    relations: Annotated[int, Field(ge=0, description="实体之间的共现关系条数")]
    topics: Annotated[int, Field(ge=0, description="主题个数（共现图上的连通分量）")]


class WikiTopicEvidenceView(ContractResponse):
    """主题页上那段可核对的原文。"""

    post_id: Annotated[int, Field(ge=1)]
    chunk_index: Annotated[int, Field(ge=0)]
    claim_text: Annotated[str, Field(min_length=1)]


class WikiTopicView(ContractResponse):
    """一个主题：一组被反复一起谈论的实体 + 它们的证据。

    `name` 是**关键词组合**（由权重最高的几个实体名拼成），不是模型拟的标题 ——
    别指望它读起来像一句话；反过来它总是诚实的：名字就是这页里的东西。
    ⚠️ 一次构建只看到这一批文章，所以主题是**增量**长出来的，不是全站快照。
    """

    name: Annotated[str, Field(min_length=1)]
    keywords: Annotated[
        list[str], Field(default_factory=list, description="这页讲什么（前几个实体）")
    ]
    entities: Annotated[list[str], Field(default_factory=list, description="规范化名字，顺序确定")]
    size: Annotated[int, Field(ge=1)]
    weight: Annotated[int, Field(ge=0, description="主题内共现边总权重")]
    post_ids: Annotated[list[int], Field(default_factory=list)]
    evidence: Annotated[list[WikiTopicEvidenceView], Field(default_factory=list)]


class WikiRelationEvidenceView(ContractResponse):
    """一条共现关系是从哪句主张里看出来的 —— 边也要能回到原文。"""

    post_id: Annotated[int, Field(ge=1)]
    chunk_index: Annotated[int, Field(ge=0)]
    claim_text: Annotated[str, Field(min_length=1)]


class WikiRelationView(ContractResponse):
    """实体之间的**共现**关系（同一句主张里同时出现）。

    ⚠️ 它**不是**语义关系（因果 / 属于 / 依赖）：那些需要模型抽取 + 人工审核。
    如实叫「共现」，`weight` 是「被一起谈论的主张条数」。
    无向边只有一种表示（`source < target`），否则 (A,B) 与 (B,A) 会各存一行、权重看着只有一半。
    """

    source: Annotated[str, Field(min_length=1, description="规范化名字（字典序较小的一端）")]
    target: Annotated[str, Field(min_length=1)]
    weight: Annotated[int, Field(ge=1, description="共同出现的主张条数")]
    evidence: Annotated[list[WikiRelationEvidenceView], Field(default_factory=list)]


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
    relations: Annotated[
        list[WikiRelationView],
        Field(default_factory=list, description="实体共现关系（边也带证据）"),
    ]
    topics: Annotated[
        list[WikiTopicView],
        Field(default_factory=list, description="主题（共现图上的连通分量，主题页的原料）"),
    ]
    stats: WikiExtractionStatsView
    notes: Annotated[list[str], Field(default_factory=list, description="给人看的提示")]
    usage_model: Annotated[str, Field(default="", description="实际使用的模型名")]
    latency_ms: Annotated[int, Field(ge=0)]
