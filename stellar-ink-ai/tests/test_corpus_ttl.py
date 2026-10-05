"""语料缓存的 TTL：它同时管**新鲜度**与**隐私窗口**。

背景（P2）：ai-service 每 5 分钟把投影表刷一遍，但它**不通知本进程**。
`corpus.py` 的语料是进程级缓存，没有 TTL 时只在首次请求时装一次、之后永不重读，
后果是双向的：

* 新发布的内容**问不到**（新鲜度）；
* **下架 / 已删 / 笔记转私有**的内容会**继续被回答**（隐私）——
  `CorpusSyncServiceImpl` 删掉投影行正是为了让私有内容不再被引用，
  而本进程缓存里那份正文还在，直到有人重启 Python。

因此本文件里最重要的不是「缓存会不会过期」，而是
`test_note_turned_private_disappears_after_ttl`：它把「转私有后不再被答」
钉成一条自动化断言，而不是靠「记得重启进程」。
"""

from __future__ import annotations

import pytest

from app.rag import corpus as corpus_module
from app.rag.content_source import SnapshotDoc


class FakeClock:
    """可控的单调时钟：不睡真实时间，也能验证 TTL 边界。"""

    def __init__(self) -> None:
        self.now = 1_000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@pytest.fixture()
def clock(monkeypatch: pytest.MonkeyPatch) -> FakeClock:
    """把语料模块的 `monotonic` 换成假时钟，并保证每个用例从「没装过语料」开始。"""
    fake = FakeClock()
    monkeypatch.setattr(corpus_module, "monotonic", fake)
    corpus_module.reset_corpus()
    yield fake
    corpus_module.reset_corpus()


def _doc(post_id: int, title: str, content: str | None = None) -> SnapshotDoc:
    body = content if content is not None else "正文内容足够切出子块。" * 3
    return SnapshotDoc(post_id=post_id, title=title, content=body, tags=[])


def _install_docs(monkeypatch: pytest.MonkeyPatch, docs: list[SnapshotDoc]) -> dict[str, int]:
    """把「读投影表」换成读一份可变列表，返回一个能数出读了几次的计数器。"""
    calls = {"n": 0}

    def loader() -> list[SnapshotDoc]:
        calls["n"] += 1
        return list(docs)

    monkeypatch.setattr(corpus_module, "load_snapshot_docs", loader)
    return calls


def test_within_ttl_corpus_is_reused(
    monkeypatch: pytest.MonkeyPatch, clock: FakeClock
) -> None:
    """TTL 之内不重读：缓存存在的意义就是「问答不必每次都把整库切一遍」。"""
    monkeypatch.setenv("AI_CORPUS_TTL_SECONDS", "60")
    calls = _install_docs(monkeypatch, [_doc(1, "第一版")])

    first = corpus_module.cached_posts()
    clock.advance(59)
    second = corpus_module.cached_posts()

    assert calls["n"] == 1
    assert second is first


def test_expired_ttl_rereads_and_bumps_epoch(
    monkeypatch: pytest.MonkeyPatch, clock: FakeClock
) -> None:
    """TTL 到期必须**换代**（EPOCH 前进），而不只是重读。

    检索管道的缓存键含 `EPOCH`：只清语料缓存会让管道继续拿着旧的块下标，
    引用就会指向另一段文字 —— 这种错「看起来一切正常」。
    """
    monkeypatch.setenv("AI_CORPUS_TTL_SECONDS", "60")
    calls = _install_docs(monkeypatch, [_doc(1, "第一版")])

    before_epoch = corpus_module.EPOCH
    assert [doc.title for doc in corpus_module.cached_posts()] == ["第一版"]

    clock.advance(60)  # 边界：到点即过期
    assert [doc.title for doc in corpus_module.cached_posts()] == ["第一版"]
    assert calls["n"] == 2
    assert corpus_module.EPOCH == before_epoch + 1


def test_note_turned_private_disappears_after_ttl(
    monkeypatch: pytest.MonkeyPatch, clock: FakeClock
) -> None:
    """**隐私验收**：上游把笔记转为私有（投影行被删）后，语料里也必须没有它。

    这条用例在接笔记之前就有意义（下架/已删的文章同理），接笔记之后它挡的是
    「私有正文留在 Python 进程内存里继续被问答引用」。
    """
    monkeypatch.setenv("AI_CORPUS_TTL_SECONDS", "60")
    docs = [_doc(1, "公开文章"), _doc(11, "后来转私有的笔记")]
    calls = _install_docs(monkeypatch, docs)

    assert [doc.title for doc in corpus_module.cached_posts()] == ["公开文章", "后来转私有的笔记"]
    chunks_before = len(corpus_module.cached_corpus())
    assert chunks_before > 0

    # 转私有：`CorpusSyncServiceImpl` 的删除侧把这一行从投影表里删掉
    docs[:] = [doc for doc in docs if doc.title != "后来转私有的笔记"]

    clock.advance(61)
    titles = [doc.title for doc in corpus_module.cached_posts()]
    assert titles == ["公开文章"], "转私有的内容仍在语料里 —— TTL 没生效，这会泄漏私有正文"
    assert len(corpus_module.cached_corpus()) < chunks_before, "切块结果也要一起换代"
    assert calls["n"] == 2


def test_zero_ttl_never_expires(monkeypatch: pytest.MonkeyPatch, clock: FakeClock) -> None:
    """`0`/负数 = 永不过期：给离线脚本与「宁可重启」的部署留出口，而不是让它们只能改代码。"""
    monkeypatch.setenv("AI_CORPUS_TTL_SECONDS", "0")
    calls = _install_docs(monkeypatch, [_doc(1, "唯一一版")])

    corpus_module.cached_posts()
    clock.advance(86_400)
    corpus_module.cached_posts()

    assert calls["n"] == 1


def test_blank_and_invalid_ttl_fall_back_to_default(monkeypatch: pytest.MonkeyPatch) -> None:
    """没配 → 默认值；配错（写成 `abc`）→ 也是默认值并留 warn。

    一个拼错的可调项不该让整站问答起不来；但也不能悄悄按「永不过期」处理 ——
    那正好是最危险的那个方向。
    """
    monkeypatch.delenv("AI_CORPUS_TTL_SECONDS", raising=False)
    assert corpus_module.ttl_seconds() == corpus_module.DEFAULT_CORPUS_TTL_SECONDS

    monkeypatch.setenv("AI_CORPUS_TTL_SECONDS", "abc")
    assert corpus_module.ttl_seconds() == corpus_module.DEFAULT_CORPUS_TTL_SECONDS
    assert corpus_module.DEFAULT_CORPUS_TTL_SECONDS > 0, "默认值必须是一个会过期的正数"


def test_previous_epoch_pipelines_are_dropped() -> None:
    """语料换代后，旧 EPOCH 的检索管道必须被丢掉。

    管道持有整份语料与 BM25 索引。缓存键含 EPOCH，如果新旧条目都留着，
    每过一轮 TTL 就多留一份语料在内存里 —— 加了 TTL 之后这就从「不会发生」
    变成了「每天上千次」。
    """
    from app.api.v1.assembly import _pipelines, pipeline_for, reset_assembly
    from app.api.v1.qa import QA_RETRIEVAL
    from tests.fake_providers import install_fake_providers

    install_fake_providers()
    reset_assembly()

    pipeline_for(QA_RETRIEVAL)
    assert len(_pipelines) == 1

    corpus_module.reset_corpus()  # TTL 到期走的就是这条路
    pipeline_for(QA_RETRIEVAL)

    assert len(_pipelines) == 1, "旧语料的管道没被丢掉：每轮 TTL 都会多留一份语料在内存里"
