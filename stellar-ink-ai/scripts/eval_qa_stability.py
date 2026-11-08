"""冻结题集的重复问答验收；每轮独立保存，语义结论必须逐轮绑定答案哈希。"""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
from pathlib import Path
from typing import Any

from app.rag.eval_service import default_dataset_path
from app.rag.eval_sources import require_saved_source_proof
from scripts.console import use_utf8_console
from scripts.eval_qa_answers import answer_hash, collect, summarize, write_result


def summarize_repeats(
    runs: list[dict[str, Any]],
    reviews: list[dict[str, Any] | None] | None = None,
) -> dict[str, Any]:
    if len(runs) < 2:
        raise ValueError("重复验收至少需要两轮结果")
    reviews = reviews if reviews is not None else [None] * len(runs)
    if len(reviews) != len(runs):
        raise ValueError("复核文件必须与运行轮数一一对应")
    first = runs[0]
    identities = {
        (r["caseId"], r["question"], tuple(r["expectedPostIds"]), r.get("expectedAnswer"))
        for r in first["rows"]
    }
    for run in runs:
        if (
            run.get("dataset") != first.get("dataset")
            or not run.get("corpusFingerprint")
            or run.get("corpusFingerprint") != first.get("corpusFingerprint")
            or not run.get("modelSignatures")
            or run.get("modelSignatures") != first.get("modelSignatures")
            or {
                (r["caseId"], r["question"], tuple(r["expectedPostIds"]), r.get("expectedAnswer"))
                for r in run["rows"]
            }
            != identities
            or len(run["rows"]) != len(identities)
        ):
            raise ValueError("题集、公开语料或实际模型不一致，不能合并成稳定性结论")

    summaries = []
    for run, review in zip(runs, reviews, strict=True):
        summary = summarize(run["rows"], review)
        checks = {c["caseId"]: c for c in (review or {}).get("cases", [])}
        positives = [r for r in run["rows"] if r["expectedPostIds"]]
        negatives = [r for r in run["rows"] if not r["expectedPostIds"]]
        factual = [
            r
            for r in run["rows"]
            if r["code"] == 0
            and r["status"] == 200
            and r["answer"].get("doneReason") == "stop"
            and r["answer"].get("evidenceSufficient") is True
        ]
        # 误拒单列；漏答只评已实际作答的可答题，避免同一误拒重复计入漏答。
        answered_positives = [
            r
            for r in positives
            if r["status"] == 200
            and r["code"] == 0
            and r["answer"].get("doneReason") not in {"refused", "error", "cancelled"}
        ]
        completeness = [
            checks[r["caseId"]]["complete"]
            for r in answered_positives
            if type(checks.get(r["caseId"], {}).get("complete")) is bool
        ]

        if any(
            c.get("complete") is not None and type(c["complete"]) is not bool
            for c in checks.values()
        ):
            raise ValueError("完整性复核必须为布尔值或null")
        summary.update(
            incompleteReviewed=len(completeness),
            incompleteAnswerRate=1 - sum(completeness) / len(completeness)
            if completeness
            else None,
            unsupportedAnswerRate=sum(
                not checks[r["caseId"]]["citationsSupported"] for r in factual
            )
            / len(factual)
            if checks and factual
            else None,
            unanswerableAnswerRate=sum(r in factual for r in negatives) / len(negatives)
            if negatives
            else None,
        )
        summaries.append(summary)

    metrics = {}
    for key in (
        "falseRefusalRate",
        "incompleteAnswerRate",
        "unsupportedAnswerRate",
        "unanswerableAnswerRate",
        "answerAccuracy",
        "literalCitationRate",
        "errors",
    ):
        values = [s[key] for s in summaries if s[key] is not None]
        metrics[key] = (
            {
                "mean": statistics.mean(values),
                "min": min(values),
                "max": max(values),
                "stdev": statistics.pstdev(values),
                "measuredRounds": len(values),
            }
            if values
            else None
        )
    cases = []
    for case_id in sorted(r["caseId"] for r in first["rows"]):
        rows = [next(r for r in run["rows"] if r["caseId"] == case_id) for run in runs]
        states = [
            (
                r["status"],
                r["code"],
                r["answer"].get("doneReason"),
                r["answer"].get("evidenceSufficient"),
            )
            for r in rows
        ]
        cases.append(
            {
                "caseId": case_id,
                "stateVariants": len(set(states)),
                "answerVariants": len({answer_hash(r) for r in rows}),
                "refusals": sum(r["answer"].get("doneReason") == "refused" for r in rows),
                "errors": sum(r["status"] != 200 or r["code"] != 0 for r in rows),
            }
        )
    return {
        "rounds": len(runs),
        "perRound": summaries,
        "metrics": metrics,
        "cases": cases,
        "stateChangedCases": sum(c["stateVariants"] > 1 for c in cases),
        "note": (
            "原文一致性不是语义正确率；未复核指标为null。重复运行共享正常缓存，不声称冷启动压测。"
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="冻结留出集重复验收与逐轮复核")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--dataset", default="holdout_v3")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--output", default="data/qa-stability.json")
    parser.add_argument("--inputs", nargs="+", help="已有逐轮结果，离线汇总不调用模型")
    parser.add_argument("--reviews", nargs="+", help="每轮对应的答案哈希复核，可额外标注complete")
    args = parser.parse_args()
    if not 2 <= args.repeat <= 10:
        parser.error("repeat 必须为2至10，避免无界消耗模型额度")
    output = Path(args.output)
    if args.inputs:
        runs = [json.loads(Path(p).read_text(encoding="utf-8")) for p in args.inputs]
    else:
        frozen = hashlib.sha256(default_dataset_path(args.dataset).read_bytes()).hexdigest()
        runs = []
        for number in range(1, args.repeat + 1):
            target = output.with_name(f"{output.stem}-round-{number}.json")
            run = collect(args.base_url, args.dataset, target)
            run["datasetFingerprint"] = frozen
            run["summary"] = summarize(run["rows"])
            write_result(target, run)
            runs.append(run)
    for run in runs:
        require_saved_source_proof(default_dataset_path(run["dataset"]), run)
    reviews = (
        [json.loads(Path(p).read_text(encoding="utf-8")) for p in args.reviews]
        if args.reviews
        else None
    )
    summary = summarize_repeats(runs, reviews)
    write_result(output, {"dataset": runs[0]["dataset"], "summary": summary})
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return int(any(s["errors"] for s in summary["perRound"]))


if __name__ == "__main__":
    use_utf8_console()
    raise SystemExit(main())
