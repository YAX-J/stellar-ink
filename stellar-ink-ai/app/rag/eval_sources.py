"""评测来源护栏：先确认标注仍对应公开原文，再允许消耗模型额度。"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Sequence
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.rag.eval_runner import EvalCase
from app.rag.evidence import chunk_text, evidence_contains, normalize_evidence
from app.rag.pipeline import IndexedChunk
from app.rag.retrieval_profile import corpus_fingerprint


class SourceBinding(BaseModel):
    model_config = ConfigDict(extra="forbid")
    case_ids: list[str] = Field(alias="caseIds", min_length=1)
    kind: Literal["post", "note"] = "post"
    id: int = Field(gt=0)
    title: str = Field(min_length=1)
    version: str | None = Field(default=None, pattern=r"^[0-9a-f]{16}$")
    quotes: list[str] = Field(min_length=1)


class AbsentSource(BaseModel):
    model_config = ConfigDict(extra="forbid")
    case_ids: list[str] = Field(alias="caseIds", min_length=1)
    title: str = Field(min_length=1)


class SourceManifest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    bindings: list[SourceBinding]
    absent: list[AbsentSource] = Field(default_factory=list)


def manifest_path(dataset_path: Path) -> Path:
    return dataset_path.with_suffix(".sources.json")


def validate_sources(
    dataset_path: Path,
    cases: Sequence[EvalCase],
    chunks: list[IndexedChunk],
) -> dict[str, Any] | None:
    """只校验本次要跑的题；无清单的旧题集保留旧行为，不宣称来源已验证。"""
    path = manifest_path(dataset_path)
    if not path.exists():
        if json.loads(dataset_path.read_text(encoding="utf-8")).get("purpose") == "holdout":
            raise ValueError("冻结留出集缺少来源清单，不能跳过校验")
        return None
    try:
        raw = path.read_bytes()
        manifest = SourceManifest.model_validate_json(raw)
    except (OSError, ValueError) as error:
        raise ValueError("评测来源清单无效，请修正清单后重新开始") from error
    selected = {case.case_id for case in cases}
    expected = {
        (case.case_id, "post", doc_id) for case in cases for doc_id in case.expected_post_ids
    }
    bound: set[tuple[str, str, int]] = set()
    by_key: dict[tuple[str, int], list[IndexedChunk]] = {}
    for chunk in chunks:
        by_key.setdefault((chunk.kind, chunk.post_id), []).append(chunk)
    issues: list[str] = []
    for binding in manifest.bindings:
        active = selected.intersection(binding.case_ids)
        if not active:
            continue
        bound.update((case_id, binding.kind, binding.id) for case_id in active)
        label = f"{','.join(sorted(active))} / {binding.kind}:{binding.id}"
        source = by_key.get((binding.kind, binding.id), [])
        if not source:
            issues.append(f"{label}：指定公开来源不存在")
            continue
        if any(normalize_evidence(c.title) != normalize_evidence(binding.title) for c in source):
            issues.append(f"{label}：标题不符，预期《{binding.title}》，实际《{source[0].title}》")
            continue
        if binding.version is not None and any(
            c.payload.get("version") != binding.version for c in source
        ):
            issues.append(f"{label}：原文版本已变化")
            continue
        if any(
            not any(evidence_contains(q, chunk_text(c)) for c in source) for q in binding.quotes
        ):
            issues.append(f"{label}：标注证据不在指定来源原文中")
    if expected - bound:
        missing = sorted(f"{case_id}/{kind}:{doc_id}" for case_id, kind, doc_id in expected - bound)
        issues.append(f"{','.join(missing)}：可答题缺少对应的来源绑定")
    for absent in manifest.absent:
        active = selected.intersection(absent.case_ids)
        if active and any(
            normalize_evidence(c.title) == normalize_evidence(absent.title) for c in chunks
        ):
            issues.append(f"{','.join(sorted(active))}：原标记缺失的来源已公开，需要重新标注")
    if issues:
        raise ValueError("评测来源校验失败（未调用模型）：" + "；".join(issues))
    return {
        "validated": True,
        "manifestFingerprint": hashlib.sha256(raw).hexdigest(),
        "corpusFingerprint": corpus_fingerprint(chunks),
        "caseIds": sorted(selected),
    }


def require_saved_source_proof(dataset_path: Path, result: dict[str, Any]) -> None:
    """离线汇总不发网络请求；有来源清单的题集必须带当轮校验证据。"""
    path = manifest_path(dataset_path)
    if not path.exists():
        if json.loads(dataset_path.read_text(encoding="utf-8")).get("purpose") == "holdout":
            raise ValueError("冻结留出集缺少来源清单，不能汇总")
        return
    proof = result.get("sourceValidation")
    if (
        not isinstance(proof, dict)
        or proof.get("validated") is not True
        or proof.get("manifestFingerprint") != hashlib.sha256(path.read_bytes()).hexdigest()
        or result.get("datasetFingerprint") != hashlib.sha256(dataset_path.read_bytes()).hexdigest()
        or proof.get("corpusFingerprint") != result.get("corpusFingerprint")
        or proof.get("caseIds") != sorted(row["caseId"] for row in result["rows"])
    ):
        raise ValueError(
            "旧报告缺少本轮来源校验证据或清单已变化，不能沿用其语义分数；请保留原报告并重新验收"
        )
