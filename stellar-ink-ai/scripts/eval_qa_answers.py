"""经网关验收最终问答；结构检查与逐题语义复核分开计数。

凭据只读 QA_EVAL_USERNAME / QA_EVAL_PASSWORD，不写入产物。
运行：uv run python scripts/eval_qa_answers.py --output data/qa-answers-current.json
离线汇总：追加 --input <已有结果> --review <逐题复核 JSON>，不再调用模型。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import statistics
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any

import httpx

from app.agents.verifier import chunk_index_of, verify
from app.rag import corpus
from app.rag.eval_runner import EvalDataset
from app.rag.eval_service import default_dataset_path
from app.rag.eval_sources import require_saved_source_proof, validate_sources
from app.rag.retrieval_profile import corpus_fingerprint
from app.schemas.common import Citation
from scripts.console import use_utf8_console


def answer_hash(row: dict[str, Any]) -> str:
    """复核绑定具体问题和完整响应，不能套用到下一轮不同答案。"""
    raw = json.dumps(
        [row["question"], row["expectedPostIds"], row.get("expectedAnswer"), row["answer"]],
        ensure_ascii=False,
        sort_keys=True,
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def summarize(rows: list[dict[str, Any]], review: dict[str, Any] | None = None) -> dict[str, Any]:
    if not rows:
        raise ValueError("没有问答结果可汇总")
    entries = (review or {}).get("cases", [])
    checks = {item["caseId"]: item for item in entries}
    if len(checks) != len(entries):
        raise ValueError("语义复核的 caseId 不能重复")
    if checks and (len(checks) != len(rows) or set(checks) != {r["caseId"] for r in rows}):
        raise ValueError("语义复核必须逐题完整覆盖本轮结果")
    for row in rows:
        if checks and checks[row["caseId"]]["answerHash"] != answer_hash(row):
            raise ValueError(f"{row['caseId']} 的语义复核已失效，答案或引用发生变化")
    if any(
        type(item.get(key)) is not bool
        for item in entries
        for key in ("correct", "citationsSupported")
    ):
        raise ValueError("语义复核结论必须是布尔值")
    positives = [r for r in rows if r["expectedPostIds"]]
    negatives = [r for r in rows if not r["expectedPostIds"]]
    cited = sum(len(r["answer"].get("citations", [])) for r in rows)
    checked = sum((r.get("structure") or {}).get("checked", 0) for r in rows)
    literal_failures = sum(
        len(
            [
                p
                for p in (r.get("structure") or {}).get("problems", [])
                if p["kind"] == "snippetNotFound"
            ]
        )
        for r in rows
    )
    successful = [r for r in rows if r["code"] == 0 and r["status"] == 200]
    latencies = sorted(r["elapsedMs"] for r in successful)

    def refused(row: dict[str, Any]) -> bool:
        return row["answer"].get("doneReason") == "refused" and not row["answer"].get(
            "evidenceSufficient",
            True,
        )

    # 支持率按实际作答题计算；拒答另计，不能因题目原本无答案就漏掉模型编造的回答。
    answered = [
        r
        for r in successful
        if r["answer"].get("doneReason") == "stop" and r["answer"].get("evidenceSufficient") is True
    ]
    supported = [checks[r["caseId"]]["citationsSupported"] for r in answered] if checks else []
    return {
        "cases": len(rows),
        "errors": len(rows) - len(successful),
        "answerableCases": len(positives),
        "unanswerableCases": len(negatives),
        "unanswerableRefusalRate": sum(refused(r) for r in negatives) / len(negatives)
        if negatives
        else None,
        "falseRefusalRate": sum(refused(r) for r in positives) / len(positives)
        if positives
        else None,
        "reviewedCases": len(checks),
        "factAnswerCases": len(answered),
        "answerAccuracy": sum(checks[r["caseId"]]["correct"] for r in rows) / len(rows)
        if checks
        else None,
        "answerableAccuracy": sum(checks[r["caseId"]]["correct"] for r in positives)
        / len(positives)
        if checks and positives
        else None,
        "citationSupportRateByAnswer": sum(supported) / len(supported) if supported else None,
        "returnedCitations": cited,
        "literalCitationRate": (checked - literal_failures) / cited if cited else None,
        "structureWarnings": sum(bool((r.get("structure") or {}).get("problems")) for r in rows),
        "totalTokens": sum(r["answer"].get("usage", {}).get("totalTokens", 0) or 0 for r in rows),
        "latencyP50Ms": statistics.median(latencies) if latencies else None,
        "latencyP95Ms": latencies[math.ceil(len(latencies) * 0.95) - 1] if latencies else None,
    }


def write_result(path: Path, result: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def collect(base_url: str, dataset_name: str, output: Path) -> dict[str, Any]:
    username, password = os.getenv("QA_EVAL_USERNAME"), os.getenv("QA_EVAL_PASSWORD")
    if not username or not password:
        raise ValueError("请显式设置 QA_EVAL_USERNAME / QA_EVAL_PASSWORD（只用于本次网关登录）")
    dataset_path = default_dataset_path(dataset_name)
    dataset = EvalDataset.load(dataset_path)
    chunks = corpus.cached_corpus()
    source_validation = validate_sources(dataset_path, dataset.cases, chunks)
    index = chunk_index_of(chunks)
    result: dict[str, Any] = {
        "dataset": dataset_name,
        "datasetFingerprint": hashlib.sha256(dataset_path.read_bytes()).hexdigest(),
        "corpusFingerprint": corpus_fingerprint(chunks),
        "rows": [],
        "sourceValidation": source_validation,
    }
    signatures: dict[str, str] = {}
    with httpx.Client(base_url=base_url, timeout=180, trust_env=False) as client:
        response = client.post("/auth/login", json={"username": username, "password": password})
        envelope = response.json()
        if not response.is_success or envelope.get("code") != 0:
            raise ValueError("网关登录失败，请检查验收账号和网关状态")
        session = envelope["data"]
        headers = {session["tokenName"]: session["tokenValue"]}
        for case in dataset.cases:
            started = time.perf_counter()
            response = client.post(
                "/ai/qa",
                headers=headers,
                json={
                    "question": case.question,
                    "topK": 5,
                },
            )
            envelope = response.json()
            answer = envelope.get("data") or {}
            structure = None
            if envelope.get("code") == 0:
                citations = [Citation.model_validate(c) for c in answer.get("citations", [])]
                structure = asdict(verify(answer.get("answer", ""), citations, index=index))
            row = {
                "caseId": case.case_id,
                "question": case.question,
                "expectedPostIds": case.expected_post_ids,
                "expectedAnswer": case.expected_answer,
                "status": response.status_code,
                "code": envelope.get("code"),
                "answer": answer,
                "structure": structure,
                "elapsedMs": round((time.perf_counter() - started) * 1000, 1),
            }
            fingerprints = answer.get("usage", {}).get("modelFingerprints") or {}
            for role, fingerprint in fingerprints.items():
                previous = signatures.get(role)
                if previous is not None and previous != fingerprint:
                    raise ValueError("验收期间生效模型已变化，不能混用结果")
                signatures[role] = fingerprint
            result["modelSignatures"] = [
                json.dumps({"role": role, "fingerprint": fingerprint}, sort_keys=True)
                for role, fingerprint in sorted(signatures.items())
            ]
            result["rows"].append(row)
            write_result(output, result)
            print(case.case_id, answer.get("doneReason"), row["elapsedMs"], flush=True)
    if result["corpusFingerprint"] != corpus_fingerprint(corpus.cached_corpus()):
        raise ValueError("问答验收期间公开语料已变化，请保留原始结果并重新开始")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="最终问答验收与可追溯逐题复核")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--dataset", default="acceptance_v1")
    parser.add_argument("--output", default="data/qa-answers-current.json")
    parser.add_argument("--input", default=None, help="离线读取已有结果，不调用模型")
    parser.add_argument("--review", default=None, help="绑定答案哈希的逐题复核结果")
    args = parser.parse_args()
    output = Path(args.output)
    result = (
        json.loads(Path(args.input).read_text(encoding="utf-8"))
        if args.input
        else collect(
            args.base_url,
            args.dataset,
            output,
        )
    )
    require_saved_source_proof(default_dataset_path(result["dataset"]), result)
    review = json.loads(Path(args.review).read_text(encoding="utf-8")) if args.review else None
    result["summary"] = summarize(result["rows"], review)
    write_result(output, result)
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    return 1 if result["summary"]["errors"] else 0


if __name__ == "__main__":
    use_utf8_console()
    raise SystemExit(main())
