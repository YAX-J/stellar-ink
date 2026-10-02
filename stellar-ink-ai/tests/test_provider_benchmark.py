"""Provider 压测的统计口径（M6）。

压测最容易出的错不是「测不准」，而是**报错的东西**：

* 把「没测到」写成 0（`p50=0ms` 看起来像「快得不可思议」）；
* 把失败请求算进延迟（一次 30 秒超时混进 P95，整个分布看起来变差，
  而真相是「有一次根本没成功」——那是失败率，不是延迟）；
* 给非流式调用编一个 TTFT（首字与整段本来就是同一个时间点）。

所以这份用例盯着的是「**没测到的东西有没有被如实标出来**」。
"""

from __future__ import annotations

import pytest

from app.providers.benchmark import Sample, detect_vram_mb, percentile, summarize


def test_percentile_uses_nearest_rank() -> None:
    """最近秩法：结果必须是**真实出现过的某个值**（样本少时插值会造出假数据）。"""
    values = [10.0, 20.0, 30.0, 40.0]

    assert percentile(values, 0.5) == 20.0
    assert percentile(values, 0.95) == 40.0
    assert percentile(values, 1.0) == 40.0
    assert percentile([7.0], 0.5) == 7.0


def test_percentile_of_nothing_is_none_not_zero() -> None:
    assert percentile([], 0.5) is None


def test_percentile_rejects_bad_quantile() -> None:
    with pytest.raises(ValueError):
        percentile([1.0], 0.0)


def test_failed_requests_are_not_in_the_latency_percentiles() -> None:
    """一次 30 秒超时不该把 P95 拉高 —— 那是失败率的问题，不是延迟分布的问题。"""
    samples = [
        Sample(latency_ms=100, ok=True),
        Sample(latency_ms=110, ok=True),
        Sample(latency_ms=120, ok=True),
        Sample(latency_ms=30_000, ok=False, error="模型服务响应超时"),
    ]

    report = summarize("chat", samples, requests=4, concurrency=1, wall_ms=1000)

    assert report.ok_count == 3
    assert report.failed_count == 1
    assert report.latency_p95_ms == 120, "失败那次不该进延迟分位"
    assert report.latency_max_ms == 120
    assert report.errors == ["模型服务响应超时"]
    assert any("没有**计入延迟分位" in note for note in report.notes)


def test_ttft_is_empty_for_non_streaming_and_says_why() -> None:
    """非流式没有 TTFT 这个量：编一个 ≈ 总延迟 的数字是最常见的假数据。"""
    report = summarize(
        "chat",
        [Sample(latency_ms=800, ok=True, completion_tokens=50)],
        requests=1,
        concurrency=1,
        wall_ms=800,
    )

    assert report.ttft_p50_ms is None
    assert any("没测到首字延迟" in note for note in report.notes)
    # 没有 TTFT 时 tokens/s 只能按整段延迟算 —— 那会**低估**真实生成速度
    assert report.tokens_per_second == pytest.approx(62.5, abs=0.5)


def test_streaming_samples_produce_ttft_and_generation_rate() -> None:
    samples = [
        Sample(latency_ms=1000, ok=True, ttft_ms=300, completion_tokens=100),
        Sample(latency_ms=1200, ok=True, ttft_ms=320, completion_tokens=120),
    ]

    report = summarize("chat", samples, requests=2, concurrency=1, wall_ms=2000)

    assert report.ttft_p50_ms == 300
    # 生成速度按「总延迟 - 首字」算：把首字等待算进去会低估模型速度
    expected = (100 + 120) / (((1000 - 300) + (1200 - 320)) / 1000)
    assert report.tokens_per_second == pytest.approx(round(expected, 2), abs=0.5)
    assert not [note for note in report.notes if "没测到首字延迟" in note]


def test_empty_run_is_not_reported_as_fast() -> None:
    report = summarize("chat", [], requests=5, concurrency=1, wall_ms=0)

    assert report.ok_count == 0
    assert report.latency_p50_ms is None, "一次都没成功，p50 只能是 None（0 会被读成「极快」）"
    assert report.requests_per_second is None
    assert any("没测到" in note for note in report.notes)


def test_embedding_has_no_token_rate_and_says_so() -> None:
    report = summarize(
        "embedding",
        [Sample(latency_ms=50, ok=True), Sample(latency_ms=60, ok=True)],
        requests=2,
        concurrency=2,
        wall_ms=100,
    )

    assert report.tokens_per_second is None
    assert any("嵌入/重排" in note for note in report.notes)


def test_concurrency_shows_up_in_throughput() -> None:
    """并发 4 跑 8 次要能算出吞吐（这是「本地推理扛不扛得住」的核心数字）。"""
    samples = [Sample(latency_ms=100, ok=True) for _ in range(8)]

    report = summarize("chat", samples, requests=8, concurrency=4, wall_ms=200)

    assert report.requests_per_second == 40.0


def test_vram_probe_never_lies_with_zero() -> None:
    """拿不到显存时必须给「原因」而不是 0：0 MB 会被读成「这个模型不吃显存」。"""
    vram, note = detect_vram_mb()

    if vram is None:
        assert note, "拿不到就要说清为什么"
    else:
        assert vram > 0
        assert "GPU" in note
