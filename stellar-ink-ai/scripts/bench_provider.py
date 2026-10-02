"""Provider 压测（M6）：TTFT / 吞吐 / P50·P95 / 显存。

用途（roadmap M6 第 4 条）：**同一套脚本**对云端模型与本地兼容服务各跑一遍，
产出可比较的数字，而不是「模型能启动」这种不算结论的结论。

两条使用方式：

    uv run python scripts/bench_provider.py --provider fake           # 离线自检：验证脚本本身
    uv run python scripts/bench_provider.py --provider panel --requests 20 --concurrency 4

⚠️ **真实数字必须在有 GPU 的机器上跑**（`--provider panel` 打到面板里配的端点）。
离线（fake）跑出来的只是「脚本能跑」，报告里会写明这一点 ——
把 fake 的延迟当成模型性能，是最容易犯也最难发现的错。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from pathlib import Path

from app.providers import runtime
from app.providers.benchmark import BenchReport, Sample, detect_vram_mb, summarize
from app.providers.config_source import ProviderConfigError, describe_sources
from app.providers.errors import ProviderError
from app.providers.fake import FakeProvider
from app.providers.models import ChatMessage, MessageRole

# 与 scripts/ 同目录，供 `uv run python scripts/x.py` 直接 import
from console import use_utf8_console

PROMPT = "用一句话说明「星笺」是什么。"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Provider 压测：TTFT / 吞吐 / P50·P95 / 显存")
    parser.add_argument(
        "--provider",
        choices=("fake", "panel"),
        default="fake",
        help="fake=离线自检（默认）；panel=面板里配的真实模型（真实数字要用它，且需要 GPU/额度）",
    )
    parser.add_argument(
        "--roles", default="chat", help="要压的角色，逗号分隔（chat,embedding,rerank）"
    )
    parser.add_argument("--requests", type=int, default=10, help="每个角色打多少次")
    parser.add_argument(
        "--concurrency", type=int, default=1, help="并发数（1 = 串行，测单请求延迟）"
    )
    parser.add_argument("--max-tokens", type=int, default=64, help="chat 的 max_tokens")
    parser.add_argument("--json", dest="json_path", default="", help="把报告写到这个文件")
    return parser.parse_args()


async def _one_chat(model: object, *, max_tokens: int) -> Sample:
    """一次 chat 调用。

    ⚠️ 只有 provider **实现流式**时才测得到 TTFT：非流式把首字与整段算成同一个时间点，
    那种「TTFT」是编出来的 —— 这里留 None，由统计层如实标注。
    """
    from app.providers.base import StreamingChatModel

    started = time.perf_counter()
    messages = [ChatMessage(MessageRole.USER, PROMPT)]
    try:
        if isinstance(model, StreamingChatModel):
            ttft_ms: float | None = None
            chunks = 0
            async for chunk in model.stream_chat(messages, max_tokens=max_tokens):
                if ttft_ms is None and chunk.text:
                    ttft_ms = (time.perf_counter() - started) * 1000
                chunks += 1
            latency_ms = (time.perf_counter() - started) * 1000
            # 流式下 token 数拿不到精确值时用「产出块数」当代理，并如实标注在 notes 里
            return Sample(latency_ms=latency_ms, ok=True, ttft_ms=ttft_ms, completion_tokens=chunks)
        response = await model.chat(messages, max_tokens=max_tokens)  # type: ignore[attr-defined]
        latency_ms = (time.perf_counter() - started) * 1000
        return Sample(
            latency_ms=latency_ms,
            ok=True,
            completion_tokens=response.usage.completion_tokens,
            prompt_tokens=response.usage.prompt_tokens,
        )
    except ProviderError as error:
        return Sample(latency_ms=(time.perf_counter() - started) * 1000, ok=False, error=str(error))
    except Exception as error:  # noqa: BLE001 - 压测要记录**任何**失败，而不是自己炸掉
        return Sample(
            latency_ms=(time.perf_counter() - started) * 1000, ok=False, error=repr(error)
        )


async def _one_embedding(model: object, *, index: int) -> Sample:
    started = time.perf_counter()
    try:
        await model.embed([f"第 {index} 段用于压测的文本"])  # type: ignore[attr-defined]
        return Sample(latency_ms=(time.perf_counter() - started) * 1000, ok=True)
    except Exception as error:  # noqa: BLE001 - 同上
        return Sample(
            latency_ms=(time.perf_counter() - started) * 1000, ok=False, error=repr(error)
        )


async def _one_rerank(model: object, *, index: int) -> Sample:
    started = time.perf_counter()
    try:
        await model.rerank(  # type: ignore[attr-defined]
            PROMPT, [f"候选 {index}-{offset}" for offset in range(8)]
        )
        return Sample(latency_ms=(time.perf_counter() - started) * 1000, ok=True)
    except Exception as error:  # noqa: BLE001 - 同上
        return Sample(
            latency_ms=(time.perf_counter() - started) * 1000, ok=False, error=repr(error)
        )


async def bench_role(
    role: str, model: object, *, requests: int, concurrency: int, max_tokens: int
) -> BenchReport:
    """压一个角色：按并发分批打，记录每次的观测。"""

    async def call(index: int) -> Sample:
        if role == "chat":
            return await _one_chat(model, max_tokens=max_tokens)
        if role == "embedding":
            return await _one_embedding(model, index=index)
        return await _one_rerank(model, index=index)

    samples: list[Sample] = []
    started = time.perf_counter()
    for begin in range(0, requests, max(concurrency, 1)):
        batch = range(begin, min(begin + concurrency, requests))
        samples.extend(await asyncio.gather(*(call(index) for index in batch)))
    wall_ms = (time.perf_counter() - started) * 1000

    report = summarize(role, samples, requests=requests, concurrency=concurrency, wall_ms=wall_ms)
    vram_mb, vram_note = detect_vram_mb()
    report.vram_mb = vram_mb
    report.vram_note = vram_note
    if role == "chat" and any(sample.ttft_ms is not None for sample in samples):
        report.notes.append(
            "流式的 completion_tokens 用的是**产出块数**（流式协议不给精确 token 数）——"
            "这个 tokens/s 只能横向比较同一种实现，不能当绝对值。"
        )
    return report


def _provider_for(role: str, provider: str) -> object:
    if provider == "fake":
        return FakeProvider()
    # panel：走与线上同一条装配路径（require_roles 会给出「去哪儿配」的可操作提示）
    runtime.require_roles(role)
    registry = runtime.registry()
    if role == "chat":
        return registry.chat_model()
    if role == "embedding":
        return registry.embedding_model()
    return registry.rerank_model()


def _print_report(report: BenchReport) -> None:
    print(f"\n=== {report.role}（{report.requests} 次 · 并发 {report.concurrency}）===")
    print(f"  成功 {report.ok_count} / 失败 {report.failed_count} · 墙钟 {report.wall_ms:.0f}ms")
    if report.latency_p50_ms is not None:
        print(
            f"  延迟 P50 {report.latency_p50_ms:.0f}ms · P95 {report.latency_p95_ms:.0f}ms · "
            f"max {report.latency_max_ms:.0f}ms（只统计成功的调用）"
        )
    if report.ttft_p50_ms is not None:
        print(f"  首字 TTFT P50 {report.ttft_p50_ms:.0f}ms")
    if report.tokens_per_second is not None:
        print(f"  生成速度 {report.tokens_per_second:.1f} tokens/s")
    if report.requests_per_second is not None:
        print(f"  吞吐 {report.requests_per_second:.2f} 请求/s")
    if report.vram_mb is None:
        print(f"  显存：未读到 —— {report.vram_note}")
    else:
        print(f"  显存占用：{report.vram_mb:.0f} MB（{report.vram_note}）")
    for note in report.notes:
        print(f"  [i] {note}")
    for error in report.errors:
        print(f"  [×] {error}")


async def main() -> int:
    args = parse_args()
    roles = [item.strip() for item in args.roles.split(",") if item.strip()]

    if args.provider == "panel":
        print(f"模型来源：面板配置（真实模型）｜{describe_sources()}")
    else:
        print("模型来源：FakeProvider（离线桩）——**这不是模型性能**，只证明脚本本身能跑")

    reports: list[BenchReport] = []
    try:
        models = [(role, _provider_for(role, args.provider)) for role in roles]
    except (ProviderError, ProviderConfigError) as error:
        print(f"✗ 装配失败：{error}")
        print(f"  配置来源：{describe_sources()}")
        return 1

    for role, model in models:
        reports.append(
            await bench_role(
                role,
                model,
                requests=args.requests,
                concurrency=args.concurrency,
                max_tokens=args.max_tokens,
            )
        )
        _print_report(reports[-1])

    payload = {
        "provider": args.provider,
        "vram": reports[0].vram_mb if reports else None,
        "vramNote": reports[0].vram_note if reports else "",
        "reports": [report.to_dict() for report in reports],
    }
    if args.provider == "fake":
        print(
            "\n[!] 这是离线桩的数字：**不能当模型性能**（它不调用任何模型）。"
            "\n    真实数字用 `--provider panel` 在有 GPU 的机器上跑，那份报告才是压测结论。"
        )
    if args.json_path:
        # 写文件放到同步函数里：async 里做阻塞 IO 会挡住事件循环（lint 规则 ASYNC240 的用意）
        _write_report(args.json_path, payload)
    return 0


def _write_report(path: str, payload: dict[str, object]) -> None:
    """把报告写成 JSON（同步函数：async 里做阻塞 IO 会挡住事件循环）。"""
    target = Path(path)
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n报告已写入：{target}")


if __name__ == "__main__":
    use_utf8_console()
    raise SystemExit(asyncio.run(main()))
