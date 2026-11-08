"""标定不能误拒可答题，也不能让单路的弱候选绕过另一路的门槛。"""

from dataclasses import replace

import pytest

from app.rag.calibration import Observation, Scope, preserves_answers, quality, recommend
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline
from app.rag.retrieval import ScoredChunk


def scope() -> Scope:
    chunks = (IndexedChunk("p1:c0", 1, "有答案"), IndexedChunk("p2:c0", 2, "无关内容"))
    return Scope(chunks, (
        Observation(frozenset({1}), (ScoredChunk(0, 15),), (ScoredChunk(0, .8),)),
        Observation(frozenset(), (ScoredChunk(1, 4),), (ScoredChunk(1, .3),)),
    ))


def test_both_routes_must_be_filtered_to_refuse() -> None:
    observed = scope()
    assert quality(observed, 5, 0).refusal_rate == 0
    assert quality(observed, 0, .5).refusal_rate == 0
    result = quality(observed, 5, .5)
    assert result.refusal_rate == 1
    assert result.false_refusal_rate == 0
    assert result.recall == 1
    assert recommend({"all": observed, "articles": observed}) is not None


def test_overlapping_scores_never_recommend_rejecting_everything() -> None:
    observed = scope()
    positive = replace(observed.rows[0], sparse=(ScoredChunk(0, 2),),
                       dense=(ScoredChunk(0, .2),))
    observed = replace(observed, rows=(positive, observed.rows[1]))
    assert recommend({"all": observed}) is None
    assert not preserves_answers(quality(observed, 20, .9), quality(observed, 0, 0))


def test_missing_class_is_not_a_calibration() -> None:
    observed = scope()
    with pytest.raises(ValueError, match="同时包含"):
        recommend({"all": replace(observed, rows=observed.rows[:1])})


async def test_calibration_matches_actual_pipeline() -> None:
    """与生产管线交叉验证，防止扫描器和真实 RRF、门槛、去重语义分叉。"""
    from app.providers.models import EmbeddingResponse, TokenUsage
    from app.rag.calibration import observe
    from app.rag.eval_runner import EvalCase, EvalDataset

    class Embedder:
        async def embed(self, texts):
            vectors = {"甲": [1., 0.], "乙": [0., 1.], "问题": [1., 0.], "无关": [-1., 0.]}
            return EmbeddingResponse([vectors[t] for t in texts], 2, TokenUsage())

    chunks = [IndexedChunk("p1:c0", 1, "甲"), IndexedChunk("p2:c0", 2, "乙")]
    dataset = EvalDataset(name="交叉验证", cases=[
        EvalCase("a", "问题", [1]), EvalCase("u", "无关"),
    ])
    embedder = Embedder()
    observed = (await observe(dataset, chunks, embedder))["all"]
    result = quality(observed, 1, .5)
    pipeline = RetrievalPipeline(
        corpus=chunks, config=RetrievalConfig(min_score=1, min_dense_score=.5), embedder=embedder,
    )
    outcomes = [await pipeline.retrieve(case.question, top_k=5) for case in dataset.cases]
    assert result.refusal_rate == int(outcomes[1].refused) == 1
    assert result.false_refusal_rate == int(outcomes[0].refused) == 0
    assert outcomes[0].posts == [1]
