"""评测台契约：`POST /eval/run`（Java `ai-service` 以 ADMIN 门槛转发给前端面板）。

字段口径：
- `strategies` 省略时用**标准五组**（sparse / dense / hybrid / hybrid+rerank / sparse+floor），
  与 `scripts/compare_strategies.py` 完全一致 —— 命令行与面板不该是两套默认值；
- `models` 如实说明这次评测的向量与重排**是谁提供的**：`fake` = 离线桩（哈希伪向量，
  无语义，只证明通路接对了），`panel` = 前端面板里配的真实模型。
  两种都可能出现，所以它不能是个常量 —— 面板用户必须一眼看出「这些数字能不能当质量结论」。
"""

from enum import StrEnum
from typing import Any

from pydantic import Field

from app.schemas.base import ContractRequest, ContractResponse

#: 对比表的列名上限：图表上放不下更多，也避免有人塞一堆列把响应撑爆
MAX_STRATEGIES = 8
#: 单次评测的题量上限（黄金集 30 题；留余量给将来的大数据集）
MAX_CASES = 500


class EvalModelSource(StrEnum):
    """评测用的模型来源。"""

    #: 离线桩：确定、零成本，但向量没有语义
    FAKE = "fake"
    #: 面板里配的真实模型（`ai_provider_config`）
    PANEL = "panel"
    #: 本次一组策略都没用到模型（只跑稀疏召回）—— 与「用了桩」必须分开，
    #: 否则「纯 BM25 的对照实验」会被读成「拿假向量跑出来的结论」
    NONE = "none"


class EvalStrategySpec(ContractRequest):
    """一组被测配置：字段与 `RetrievalConfig` 一一对应（开关即前端实验室的开关）。"""

    key: str = Field(min_length=1, max_length=32, description="策略标识（对比表的列名，需唯一）")

    enable_sparse: bool = Field(default=True, description="启用 BM25 召回")
    enable_dense: bool = Field(default=True, description="启用向量召回")
    enable_rerank: bool = Field(default=False, description="启用重排（只对候选做）")

    top_k: int = Field(default=10, ge=1, le=100, description="评估深度（算 Recall@K 用）")
    candidate_k: int = Field(default=30, ge=1, le=100, description="每路召回的候选数")
    sparse_weight: float = Field(default=1.0, ge=0, description="RRF 里 BM25 的权重")
    dense_weight: float = Field(default=1.0, ge=0, description="RRF 里向量的权重")
    rrf_k: int = Field(default=60, ge=1, description="RRF 的 k（越大越弱化头部名次）")
    min_score: float = Field(default=0.0, ge=0, description="BM25 绝对下限（唯一能触发拒答）")
    min_score_ratio: float = Field(
        default=0.0, ge=0, lt=1, description="BM25 相对门限（只提精度，不会拒答）"
    )
    min_dense_score: float = Field(
        default=0.0, ge=-1, le=1, description="余弦下限（Dense 通路的拒答机制）"
    )
    rerank_top_n: int = Field(default=10, ge=1, le=100, description="重排后保留的候选数")

    enable_graph: bool = Field(
        default=False,
        description="启用图检索（E5-2）：走知识图的实体与共现边，**一次模型都不调**。"
        "它需要请求里带上 `graph`（一次抽取的返回体）；没带就如实回一条「没有图」的行，"
        "而不是静悄悄少一列",
    )


class EvalRunRequest(ContractRequest):
    """``POST /eval/run`` 的请求体。"""

    dataset: str = Field(default="golden_v1", min_length=1, max_length=64, description="数据集标识")

    strategies: list[EvalStrategySpec] = Field(
        default_factory=list,
        max_length=MAX_STRATEGIES,
        description="被测配置；为空时用标准五组",
    )

    graph: dict[str, Any] | None = Field(
        default=None,
        description="知识图（一次 `/wiki/claims` 返回体）；只有开了 `enableGraph` 才需要。"
        "图检索的上限由**图的覆盖率**决定，所以「用哪一份图跑的评测」必须由调用方显式给出，"
        "不能在服务端偷偷用缓存（那样两次评测的数字不可比）",
    )

    max_cases: int | None = Field(
        default=None, ge=1, le=MAX_CASES, description="只跑前 N 题（调试用；为空则全跑）"
    )

    def resolved_keys(self) -> list[str]:
        """请求里显式给出的策略 key（保持顺序）。"""
        return [strategy.key for strategy in self.strategies]


class EvalCaseResultRow(ContractResponse):
    """逐题明细：前端下钻「这题为什么没召回」时就靠它。"""

    case_id: str = Field(description="题目 ID")
    strategy: str = Field(description="策略 key")
    question: str = Field(description="问题原文")
    case_type: str = Field(description="answerable | unanswerable")
    retrieved_posts: list[int] = Field(default_factory=list, description="检索到的文章（按名次）")
    relevant_posts: list[int] = Field(default_factory=list, description="标注的相关文章")
    refused: bool = Field(description="本题是否拒答")
    latency_ms: float = Field(ge=0, description="单题耗时（毫秒）")


class EvalStrategySummary(ContractResponse):
    """策略摘要：让前端能把 key 还原成人类可读说明与开关组合。"""

    key: str = Field(description="策略 key")
    description: str = Field(description="人类可读说明（含开关组合）")


class EvalRunResponse(ContractResponse):
    """``POST /eval/run`` 的返回体：指标表 + 逐题明细 + 数据来源说明。"""

    dataset: str = Field(description="数据集标识")
    dataset_description: str = Field(default="", description="数据集说明")
    corpus_source: str = Field(description="语料来源（例：seed-sql:02-init-data.sql）")
    corpus_posts: int = Field(ge=0, description="语料文章数")
    corpus_chunks: int = Field(ge=0, description="语料子块数")
    models: EvalModelSource = Field(description="本次评测用的模型来源")
    ks: list[int] = Field(default_factory=list, description="评估用到的 K")
    strategies: list[EvalStrategySummary] = Field(
        default_factory=list, description="本次跑的策略（顺序即对比表列序）"
    )
    per_strategy: dict[str, dict[str, float | int | str | list[str] | None]] = Field(
        default_factory=dict, description="{策略: 指标}，即前端对比表"
    )
    cases: list[EvalCaseResultRow] = Field(default_factory=list, description="逐题明细")
    elapsed_ms: float = Field(ge=0, description="整轮耗时（毫秒）")
    notes: list[str] = Field(
        default_factory=list, description="诚实提示（如 Fake 向量无语义、拒答阈值口径）"
    )
