"""用当前公开语料标定混合检索，并用独立题集验收冻结后的阈值。

输出报告默认只在本地 data/；只有独立验收通过才写入可启用的 profile。
运行：uv run python scripts/calibrate_hybrid_score.py --acceptance acceptance_v1
"""

from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import asdict
from pathlib import Path
from typing import Any

from app.providers import runtime
from app.providers.config_source import ProviderConfigError
from app.providers.errors import ProviderError
from app.rag import corpus
from app.rag.calibration import Scope, observe, preserves_answers, quality, recommend
from app.rag.eval_runner import EvalDataset
from app.rag.eval_service import default_dataset_path
from app.rag.eval_sources import validate_sources
from app.rag.retrieval_profile import RetrievalProfile, corpus_fingerprint
from scripts.console import use_utf8_console


def load_calibration_dataset(name: str) -> EvalDataset:
    path = default_dataset_path(name)
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("purpose") in {"acceptance", "holdout"}:
        raise ValueError("独立验收集或冻结留出集不能用于标定阈值")
    return EvalDataset.load(path)


def summarize(scopes: dict[str, Scope], floors: tuple[float, float]) -> dict[str, Any]:
    return {
        name: {
            "baseline": asdict(quality(scope, 0, 0)),
            "calibrated": asdict(quality(scope, *floors)),
        }
        for name, scope in scopes.items()
    }


def write_profile(path: str, profile: RetrievalProfile) -> str:
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(profile.model_dump_json(indent=2), encoding="utf-8")
    return str(output.resolve())


async def run(args: argparse.Namespace) -> dict[str, Any]:
    dataset = load_calibration_dataset(args.dataset)
    chunks = corpus.cached_corpus()
    validate_sources(default_dataset_path(args.dataset), dataset.cases, chunks)
    acceptance = None
    if args.acceptance:
        acceptance = EvalDataset.load(default_dataset_path(args.acceptance))
        validate_sources(default_dataset_path(args.acceptance), acceptance.cases, chunks)
        if {c.question for c in dataset.cases} & {c.question for c in acceptance.cases}:
            raise ValueError("标定集与独立验收集有重复问题")
    signature = corpus_fingerprint(chunks)
    registry = runtime.registry()
    config = registry.config_of("embedding")
    if config is None or config.provider == "fake":
        raise ValueError("阈值标定需要面板中配置的真实嵌入模型")
    embedder = registry.embedding_model()
    try:
        scopes = await observe(dataset, chunks, embedder)
        floors = recommend(scopes)
        if floors is None:
            raise ValueError("当前分数分布无法在保住答案的约束下提高拒答率，不写入阈值")
        report: dict[str, Any] = {
            "dataset": args.dataset,
            "embedding": config.safe_summary(),
            "corpusFingerprint": signature,
            "chunks": len(chunks),
            "minScore": floors[0],
            "minDenseScore": floors[1],
            "calibration": summarize(scopes, floors),
            "validated": False,
        }
        if acceptance is not None:
            # 此处参数已冻结，验收集不参与阈值推荐。
            accepted = await observe(acceptance, chunks, embedder)
            report["acceptanceDataset"] = args.acceptance
            report["acceptance"] = summarize(accepted, floors)
            report["validated"] = all(
                preserves_answers(quality(scope, *floors), quality(scope, 0, 0))
                and quality(scope, *floors).refusal_rate > quality(scope, 0, 0).refusal_rate
                for scope in accepted.values()
            )
        # 防止模型/公开状态在长任务期间变化后，误把旧标定写成当前可用。
        if signature != corpus_fingerprint(corpus.cached_corpus()):
            raise ValueError("标定期间公开语料已变化，请重新运行")
        current = runtime.registry().config_of("embedding")
        if current is None or current.fingerprint() != config.fingerprint():
            raise ValueError("标定期间嵌入模型已变化，请重新运行")
        if report["validated"]:
            profile = RetrievalProfile(
                embedding_fingerprint=config.fingerprint(),
                corpus_fingerprint=signature,
                min_score=floors[0],
                min_dense_score=floors[1],
                validated=True,
            )
            report["profilePath"] = await asyncio.to_thread(
                write_profile,
                args.profile_output,
                profile,
            )
        return report
    finally:
        await registry.aclose()


def main() -> int:
    parser = argparse.ArgumentParser(description="两路阈值标定与独立验收")
    parser.add_argument("--dataset", default="golden_v1")
    parser.add_argument("--acceptance", default=None)
    parser.add_argument("--profile-output", default="data/retrieval-profile.json")
    parser.add_argument("--report", default="data/retrieval-calibration.json")
    args = parser.parse_args()
    try:
        report = asyncio.run(run(args))
    except (ProviderError, ProviderConfigError, ValueError) as error:
        print(f"无法完成标定：{error}")
        return 1
    target = Path(args.report)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["validated"] else 1


if __name__ == "__main__":
    use_utf8_console()
    raise SystemExit(main())
