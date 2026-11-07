"""撤回不依赖 TTL；复核失败不得放行旧正文，空库不得复活种子内容。"""

from collections.abc import Iterator
from contextlib import contextmanager

import httpx
import pytest

from app.rag import corpus, public_corpus
from app.rag.content_source import CorpusSourceUnavailable, SnapshotDoc


def doc(kind: str = "post", content_id: int = 1) -> SnapshotDoc:
    return SnapshotDoc(post_id=content_id, title="标题", content="公开正文", tags=[], kind=kind)


def install_public(monkeypatch: pytest.MonkeyPatch, documents: list[SnapshotDoc]) -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/corpus"
        requested = set(map(int, request.url.params["ids"].split(",")))
        return httpx.Response(200, json={"code": 0, "data": {
            "truncated": False,
            "items": [{"kind": value.kind, "id": value.post_id,
                       "docHash": public_corpus.document_hash(value)}
                      for value in documents if value.post_id in requested],
        }})
    client_type = httpx.Client

    @contextmanager
    def client(**_: object) -> Iterator[httpx.Client]:
        with client_type(transport=httpx.MockTransport(handle)) as instance:
            yield instance

    monkeypatch.setattr(public_corpus.httpx, "Client", client)


def test_same_id_across_kinds_does_not_allow_private_note(monkeypatch: pytest.MonkeyPatch) -> None:
    install_public(monkeypatch, [doc("post")])
    assert public_corpus.visible_docs([doc("post"), doc("note")]) == (doc("post"),)


def test_old_public_version_is_excluded(monkeypatch: pytest.MonkeyPatch) -> None:
    current = doc()
    current = SnapshotDoc(post_id=1, title=current.title, content="已经修改", tags=[])
    install_public(monkeypatch, [current])
    assert public_corpus.visible_docs([doc()]) == ()


def test_recheck_is_batched_without_truncation(monkeypatch: pytest.MonkeyPatch) -> None:
    documents = [doc(content_id=number) for number in range(1, 402)]
    install_public(monkeypatch, documents)
    assert public_corpus.visible_docs(documents) == tuple(documents)


@pytest.mark.parametrize("body", [
    {"code": 500, "data": None},
    {"code": 0, "data": {"truncated": True, "items": []}},
    {"code": 0, "data": {"items": []}},
])
def test_failed_or_partial_check_is_closed(monkeypatch: pytest.MonkeyPatch, body: dict) -> None:
    original = httpx.Client
    monkeypatch.setattr(public_corpus.httpx, "Client", lambda **_: original(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=body))))
    with pytest.raises(CorpusSourceUnavailable):
        public_corpus.visible_docs([doc()])


def test_withdrawal_removes_cached_chunks_without_waiting_for_sync(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_CORPUS_SOURCE", "online")
    monkeypatch.setenv("AI_CORPUS_TTL_SECONDS", "3600")
    snapshots = [doc("post"), doc("note")]
    monkeypatch.setattr(corpus, "load_snapshot_docs", lambda: snapshots)
    upstream = list(snapshots)
    install_public(monkeypatch, upstream)
    assert len(corpus.cached_posts()) == 2
    assert len(corpus.cached_corpus()) == 2
    epoch = corpus.EPOCH
    upstream.pop()
    assert corpus.cached_posts() == (doc("post"),)
    assert all(chunk.kind == "post" for chunk in corpus.cached_corpus())
    assert corpus.EPOCH == epoch + 1
    assert len(snapshots) == 2, "投影尚未同步，隐私仍必须生效"


def test_online_empty_corpus_does_not_fall_back_to_seed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_CORPUS_SOURCE", "online")
    monkeypatch.setattr(corpus, "load_snapshot_docs", lambda: [])
    assert corpus.cached_posts() == ()
    assert corpus.cached_corpus() == []


def test_online_failure_does_not_fall_back_to_seed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_CORPUS_SOURCE", "online")

    def unavailable() -> list[SnapshotDoc]:
        raise CorpusSourceUnavailable("数据库不可用")

    monkeypatch.setattr(corpus, "load_snapshot_docs", unavailable)
    with pytest.raises(CorpusSourceUnavailable):
        corpus.cached_posts()


def test_production_rejects_seed_even_with_a_warm_cache(monkeypatch: pytest.MonkeyPatch) -> None:
    corpus.cached_posts()
    monkeypatch.setenv("AI_APP_ENV", "prod")
    with pytest.raises(CorpusSourceUnavailable):
        corpus.cached_posts()
