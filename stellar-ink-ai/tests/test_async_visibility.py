"""公开状态复核被人为暂停时，事件循环仍可运行；撤回与异常仍然生效。"""

import asyncio
import threading

import pytest

from app.rag.content_source import CorpusSourceUnavailable
from app.rag.pipeline import IndexedChunk, RetrievalConfig, RetrievalPipeline


async def test_slow_visibility_check_does_not_block_the_event_loop() -> None:
    entered = asyncio.Event()
    loop = asyncio.get_running_loop()
    release = threading.Event()
    chunk = IndexedChunk(chunk_id="c1", post_id=1, title="写作", text="写作方法", payload={})
    loop_thread = threading.get_ident()
    workers = []

    def refresh():
        workers.append(threading.get_ident())
        loop.call_soon_threadsafe(entered.set)
        if not release.wait(timeout=2):
            raise AssertionError("事件循环未能释放复核任务")
        return [chunk]

    pipeline = RetrievalPipeline(
        corpus=[chunk],
        config=RetrievalConfig(enable_dense=False),
        refresh_corpus=refresh,
    )
    task = asyncio.create_task(pipeline.retrieve("写作", top_k=1))
    try:
        await asyncio.wait_for(entered.wait(), timeout=0.5)
        assert not task.done(), "复核仍在等待时，本协程已经获得了运行机会"
        assert all(worker != loop_thread for worker in workers)
    finally:
        release.set()
        await task


async def test_withdrawal_during_retrieval_is_filtered() -> None:
    chunk = IndexedChunk(chunk_id="c1", post_id=1, title="写作", text="写作方法", payload={})
    reads = 0

    def refresh():
        nonlocal reads
        reads += 1
        return [chunk] if reads == 1 else []

    pipeline = RetrievalPipeline(
        corpus=[chunk],
        config=RetrievalConfig(enable_dense=False),
        refresh_corpus=refresh,
    )
    result = await pipeline.retrieve("写作", top_k=1)
    assert result.refused and not result.hits


async def test_visibility_error_still_fails_closed() -> None:
    def refresh():
        raise CorpusSourceUnavailable("内容服务不可达")

    pipeline = RetrievalPipeline(
        corpus=[],
        config=RetrievalConfig(enable_dense=False),
        refresh_corpus=refresh,
    )
    with pytest.raises(CorpusSourceUnavailable, match="内容服务不可达"):
        await pipeline.retrieve("写作", top_k=1)
