"""使用语料前实时复核公开版本；可见性规则只由 content-service 判定。

只读取内部公开清单，不读业务表。每次使用都复核，结果不跨请求缓存；
连接失败、业务失败或清单截断时停止使用旧语料，不能退回种子内容。
"""

from __future__ import annotations

import hashlib
import os
from collections.abc import Sequence

import httpx

from app.rag.content_source import CorpusSourceUnavailable
from app.rag.pipeline import PostLike


def document_hash(doc: PostLike) -> str:
    """与 Java Hashes.docHash 一致：统一换行后去掉首尾空白。"""
    def normalize(value: str) -> str:
        return value.replace("\r\n", "\n").replace("\r", "\n").strip()

    return hashlib.sha256(f"{normalize(doc.title)}\n{normalize(doc.plain)}".encode()).hexdigest()


def visible_docs(docs: Sequence[PostLike]) -> tuple[PostLike, ...]:
    """按唯一文档标识及正文版本取交集，旧版本即使仍公开也不得再送给模型。"""
    if not docs:
        return ()
    ids = sorted({doc.post_id for doc in docs})
    versions: dict[tuple[str, int], str] = {}
    base = os.environ.get("CONTENT_SERVICE_URL", "http://127.0.0.1:8102").rstrip("/")
    try:
        # 同 ID 最多对应两种内容，200 个 ID 最多 400 条，不会越过服务端 1000 条上限。
        with httpx.Client(timeout=3.0, trust_env=False) as client:
            for start in range(0, len(ids), 200):
                batch = ids[start : start + 200]
                response = client.get(
                    f"{base}/internal/corpus",
                    params={"ids": ",".join(map(str, batch)), "limit": 1000},
                )
                response.raise_for_status()
                envelope = response.json()
                page = envelope.get("data")
                if envelope.get("code") != 0 or not isinstance(page, dict):
                    raise ValueError("公开清单业务失败")
                if page.get("truncated") is not False or not isinstance(page.get("items"), list):
                    raise ValueError("公开清单不完整")
                for item in page["items"]:
                    kind = item.get("kind")
                    content_id, doc_hash = item.get("id"), item.get("docHash")
                    if (kind not in {"post", "note"} or content_id not in batch
                            or not isinstance(doc_hash, str) or len(doc_hash) != 64):
                        raise ValueError("公开清单条目无效")
                    key = (str(kind), int(content_id))
                    if key in versions:
                        raise ValueError("公开清单条目重复")
                    versions[key] = doc_hash
    except (httpx.HTTPError, ValueError, TypeError, AttributeError) as error:
        raise CorpusSourceUnavailable("无法复核语料公开状态，请检查内容服务后重试") from error
    return tuple(doc for doc in docs if versions.get((doc.kind, doc.post_id)) == document_hash(doc))
