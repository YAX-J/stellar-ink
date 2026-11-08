"""只将通过独立验收且模型、公开语料均匹配的标定结果用于问答。"""

from __future__ import annotations

import hashlib
import logging
import os
from dataclasses import replace
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.core.config import PROJECT_ROOT
from app.providers.config_source import ProviderConfigError
from app.providers.models import ProviderConfig
from app.rag.pipeline import IndexedChunk, RetrievalConfig

logger = logging.getLogger(__name__)


class RetrievalProfile(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    embedding_fingerprint: str
    corpus_fingerprint: str
    min_score: float = Field(ge=0)
    min_dense_score: float = Field(ge=0, le=1)
    validated: Literal[True]


def corpus_fingerprint(chunks: list[IndexedChunk]) -> str:
    """正文与顺序参与指纹：BM25 分数还依赖整份语料的词频统计。"""
    raw = "\n".join(f"{c.kind}:{c.chunk_id}:{c.text}" for c in chunks)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def load_profile() -> RetrievalProfile | None:
    configured = os.environ.get("AI_RETRIEVAL_PROFILE_PATH", "").strip()
    if not configured:
        return None
    path = Path(configured)
    if not path.is_absolute():
        path = PROJECT_ROOT / path
    try:
        return RetrievalProfile.model_validate_json(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ProviderConfigError(
            "检索标定文件无效，请重新标定或检查 AI_RETRIEVAL_PROFILE_PATH"
        ) from error


def apply_profile(
    config: RetrievalConfig, chunks: list[IndexedChunk], embedding: ProviderConfig,
) -> RetrievalConfig:
    profile = load_profile()
    if profile is None:
        return config
    if (profile.embedding_fingerprint != embedding.fingerprint()
            or profile.corpus_fingerprint != corpus_fingerprint(chunks)):
        # 保持既有未标定行为，避免新发布一篇文章就使问答不可用；不得沿用失效阈值。
        logger.warning("检索标定已失效：模型或公开语料发生变化，请重新标定；本次使用未标定配置")
        return config
    return replace(
        config, candidate_k=30, min_score=profile.min_score,
        min_dense_score=profile.min_dense_score,
    )
