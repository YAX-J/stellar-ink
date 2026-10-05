"""A2 确定性引用核验：`app/agents/verifier.py` + `POST /agent/verify`。

这一层守的是**判定本身**，不是措辞：

1. 三类问题各要有独立断言（编号越界 / 未标编号 / 片段与原文对不上），
   并且要能从 `problems[].kind` 分辨出来 —— 混成一类就没法反馈到界面；
2. **全对时 `ok`、有问题时 `warn`**，且 `problems` 是人话；
3. **零模型调用**：核验的钱必须真的不花（这是它能随手点、也能进单测的前提）；
4. `ok` **不等于「答案是对的」**：`checked` / `evidenceAvailable` 必须一起给出，
   否则界面会把「一条都没核对」显示成「引用没问题」。

语料一律用**内联的一小块**（`CORPUS`），不读 `cached_corpus()`：
后者在开发机上读的是线上投影表，测试会随「本机库里有什么」变化 —— 那不叫测试。
"""

from __future__ import annotations

import json
from typing import Any

import pytest
from fastapi import FastAPI

from app.agents import registry, verifier
from app.api.v1.agent import to_verify_result
from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from app.providers.models import ChatMessage, ChatResponse, TokenUsage
from app.rag.agent import parse_citation_marks
from app.rag.pipeline import IndexedChunk
from app.schemas.common import Citation
from tests.fake_providers import install_no_providers
from tests.signing import FIXED_NONCE, FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

#: 内联语料：两段原文，核验的「原文」这一侧就是它们
SOURCES = (
    "每天写五百字，一年就是十八万字，靠的是复利。",
    "把超时显式写出来，比默认无限等要安全得多。",
)


def corpus() -> list[IndexedChunk]:
    """把它切成两块（块下标即引用里的 `chunkIndex`）。"""
    return [
        IndexedChunk(
            chunk_id=f"c{index}",
            post_id=index + 1,
            text=f"标题 {index + 1}\n{text}",
            payload={"text": text, "chunkIndex": index},
            title=f"标题 {index + 1}",
        )
        for index, text in enumerate(SOURCES)
    ]


def cite(
    index: int = 0, *, snippet: str | None = None, post_id: int | None = None
) -> dict[str, Any]:
    """造一条引用（默认就是「片段逐字来自原文」的那一条）。"""
    resolved = post_id if post_id is not None else index + 1
    return {
        "postId": resolved,
        "title": f"标题 {resolved}",
        "chunkIndex": index,
        "snippet": snippet if snippet is not None else SOURCES[index],
    }


def verified(answer: str, citations: list[dict[str, Any]]) -> verifier.VerificationReport:
    """跑一次核验（引用由 dict 造，交给契约反序列化 —— 与端点同一条入口）。"""
    return verifier.verify(
        answer,
        [Citation.model_validate(item) for item in citations],
        index=verifier.chunk_index_of(corpus()),
    )


class RecordingChat:
    """一个**会记账**的模型桩：有调用就记下来（用来断言「零调用」）。"""

    MODEL_TAG = "should-never-be-called"

    def __init__(self) -> None:
        self.calls: list[list[ChatMessage]] = []

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResponse:
        self.calls.append(list(messages))
        return ChatResponse(text="不该被调用", usage=TokenUsage(model=self.MODEL_TAG))


# --------------------------------------------------------------- 判定本身


def test_all_correct_is_ok() -> None:
    """全对：`ok`、`checked` 等于核对过的条数、没有问题清单。"""
    report = verified("靠的是每天五百字 [1]，超时要显式写 [2]。", [cite(0), cite(1)])

    assert report.verdict == verifier.VERDICT_OK
    assert report.ok is True
    assert report.problems == []
    assert report.checked == 2, "两条都回查到了原文"
    assert report.cited_indexes == [1, 2]
    assert report.out_of_range == []
    assert report.uncited is False


def test_out_of_range_mark_is_reported() -> None:
    """问题一：答案标了 `[3]`，但只有 2 条引用。"""
    report = verified("靠的是每天五百字 [3]。", [cite(0), cite(1)])

    assert report.verdict == verifier.VERDICT_WARN
    assert report.out_of_range == [3]
    kinds = [problem.kind for problem in report.problems]
    assert verifier.PROBLEM_OUT_OF_RANGE in kinds
    message = next(p.message for p in report.problems if p.kind == verifier.PROBLEM_OUT_OF_RANGE)
    assert "[3]" in message and "2 条引用" in message


def test_zero_and_huge_marks_are_both_out_of_range() -> None:
    """`[0]` 也越界（编号从 1 开始）—— 这是最容易漏的一侧。"""
    report = verified("看 [0] 与 [99]。", [cite(0)])

    assert report.out_of_range == [0, 99]
    assert report.verdict == verifier.VERDICT_WARN


def test_uncited_answer_is_reported() -> None:
    """问题二：**有引用却一个编号都没标**（这不等于「没有依据」）。"""
    report = verified("靠的是每天五百字。", [cite(0), cite(1)])

    assert report.verdict == verifier.VERDICT_WARN
    assert report.uncited is True
    assert report.cited_indexes == []
    kinds = [problem.kind for problem in report.problems]
    assert verifier.PROBLEM_UNCITED in kinds, "要与「越界」分开：处置完全不同"
    message = next(p.message for p in report.problems if p.kind == verifier.PROBLEM_UNCITED)
    assert "引用编号都没标" in message and "2 条引用" in message


def test_uncited_without_citations_is_not_a_warning() -> None:
    """没有引用 = 本来就没有「哪句来自哪条」可标：不该报未标编号。

    这正是「拒答」的形态（`answer` 里就写着「没有找到依据」），
    把它标成 warn 会让界面把一次正常的拒答显示成「引用有问题」。
    """
    report = verified("这几篇文章里没有找到能回答这个问题的依据。", [])

    assert report.verdict == verifier.VERDICT_OK
    assert report.uncited is False
    assert report.problems == []
    assert report.checked == 0
    assert report.evidence_available is True


def test_snippet_not_in_source_is_reported() -> None:
    """问题三：片段被改写过（原文是「把超时显式写出来」，这里多了个「地」）。"""
    report = verified(
        "超时要显式写 [1]。", [cite(0, snippet="把超时显式地写出来，比默认无限等要安全得多。")]
    )

    assert report.verdict == verifier.VERDICT_WARN
    kinds = [problem.kind for problem in report.problems]
    assert verifier.PROBLEM_SNIPPET_NOT_FOUND in kinds
    message = next(
        p.message for p in report.problems if p.kind == verifier.PROBLEM_SNIPPET_NOT_FOUND
    )
    assert "找不到" in message and "post" in message


def test_snippet_whitespace_differences_are_tolerated() -> None:
    """换个行、多个空格不算「引用不实」：与 Wiki 的 quote 校验同一套规范化。"""
    snippet = SOURCES[1][:9] + "\n  " + SOURCES[1][9:]
    report = verified("超时要显式写 [1]。", [cite(1, snippet=snippet)])

    assert report.verdict == verifier.VERDICT_OK
    assert report.checked == 1


def test_too_short_snippet_is_not_accepted() -> None:
    """短于阈值的片段证明不了任何事：宁可不判，也不要给一个看起来很确定的结论。"""
    report = verified("看 [1]。", [cite(0, snippet="复利")])

    assert verifier.PROBLEM_SNIPPET_NOT_FOUND in [p.kind for p in report.problems]


def test_missing_chunk_is_reported_as_unknown_chunk() -> None:
    """引用指向的段落不在语料里：可能是文章改过，也可能索引要重建 —— 不猜是哪一种。"""
    citation = cite(0)
    citation["chunkIndex"] = 7
    report = verified("看 [1]。", [citation])

    kinds = [problem.kind for problem in report.problems]
    assert verifier.PROBLEM_UNKNOWN_CHUNK in kinds
    assert verifier.PROBLEM_SNIPPET_NOT_FOUND not in kinds, "查不到原文与片段对不上是两件事"


def test_note_and_post_are_different_documents() -> None:
    """文档标识是 `kind + postId`：文章 1 与笔记 1 不是同一篇。"""
    citation = cite(0)
    citation["kind"] = "note"
    report = verified("看 [1]。", [citation])

    assert verifier.PROBLEM_UNKNOWN_CHUNK in [p.kind for p in report.problems]


def test_checked_counts_resolved_chunks_not_matching_snippets() -> None:
    """`checked` 是「回查到原文几条」，不是「片段对上了几条」——它是 `ok` 的分母。"""
    report = verified("看 [1] 与 [2]。", [cite(0), cite(1, snippet="这段原文里根本没有")])

    assert report.checked == 2
    assert report.verdict == verifier.VERDICT_WARN


def test_without_an_index_the_report_says_evidence_is_unavailable() -> None:
    """没有原文可查时必须**说出来**：`checked=0` 配 `evidenceAvailable=false`。

    少了这个字段，界面只能看到 `verdict=ok + checked=0`，
    而那与「比了三条都没问题」长得一模一样 —— 那正是「把没查当成没问题」的形态。
    """
    report = verifier.verify("答案 [1]。", [Citation.model_validate(cite(0))])

    assert report.evidence_available is False
    assert report.checked == 0
    assert report.verdict == verifier.VERDICT_OK


def test_repeated_and_duplicate_marks_are_listed_once() -> None:
    """同一个编号出现多次只记一次（按出现顺序）——前端渲染成标签时会重复。"""
    assert parse_citation_marks("先是 [2]，又 [1]，再 [2]。") == [2, 1]


def test_marks_only_count_bracketed_numbers() -> None:
    """`[见上文]`、`[1,2]` 都不算编号：猜它的语义会把「没标」说成「标了」。"""
    assert parse_citation_marks("见 [见上文] 与 [1,2] 以及 [3]") == [3]


def test_verifier_profile_is_registered_and_executable() -> None:
    """司职仍在注册表里可见，而且**不再**是「尚未接线」。"""
    profile = registry.get_profile("verifier")

    assert profile.name == "verifier"
    assert profile in registry.list_profiles()
    assert "verifier" in registry.EXECUTABLE_AGENTS
    assert "verifier" not in registry.SKELETON_AGENTS
    assert "verifier" in registry.MODEL_FREE_AGENTS


def test_mapping_to_the_contract_carries_every_field() -> None:
    """翻译层只搬字段：`verdict`/`checked`/`problems` 一个都不能掉。"""
    report = verified("靠的是每天五百字 [9]。", [cite(0)])

    result = to_verify_result(report)

    assert result.verdict == "warn"
    assert result.out_of_range == [9]
    assert result.problems[0].kind == verifier.PROBLEM_OUT_OF_RANGE
    assert result.problems[0].message


def test_verifier_imports_no_chat_model() -> None:
    """判定层**不持有模型**：它只吃「答案 + 引用 + 原文」。

    这条断言守的是设计约束而不是实现细节：一旦有人往这里塞一个 `ChatModel`，
    「零模型调用」就只剩一句注释了。
    """
    import ast
    from pathlib import Path

    tree = ast.parse(Path(verifier.__file__ or "").read_text(encoding="utf-8"))
    imported = {
        alias.name
        for node in ast.walk(tree)
        if isinstance(node, ast.Import)
        for alias in node.names
    } | {
        node.module or ""
        for node in ast.walk(tree)
        if isinstance(node, ast.ImportFrom)
    }

    assert "app.providers.base" not in imported
    assert "app.providers.models" not in imported


# --------------------------------------------------------------- 端点


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    # **刻意一个模型都不配**：核验不该被「面板还没配 chat/embedding」卡住
    install_no_providers()
    return create_app(verifier=InternalRequestVerifier(secret))


async def post_verify(
    app: FastAPI, secret: str, payload: dict, *, nonce: str = FIXED_NONCE
) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers(
            "POST", "/agent/verify", secret=secret, body=body, role="AUTHOR", user_id=1, nonce=nonce
        ),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", "/agent/verify", headers=headers, content=body)
    return response.status_code, response.json()


async def test_verify_requires_signature(app: FastAPI) -> None:
    response = await call(app, "POST", "/agent/verify", json={"answer": "x", "citations": []})

    assert response.status_code == 401


async def test_verify_endpoint_runs_without_any_model(app: FastAPI, secret: str) -> None:
    """**零模型调用**：连 chat/embedding 都没配，核验照样给出结论。"""
    status, payload = await post_verify(
        app,
        secret,
        {"answer": "靠的是每天五百字 [9]。", "citations": [cite(0)]},
    )

    assert status == 200, payload
    assert payload["verdict"] == "warn"
    assert payload["outOfRange"] == [9]
    assert payload["evidenceAvailable"] is True
    assert payload["checked"] >= 0
    assert set(payload) == {
        "verdict",
        "checked",
        "evidenceAvailable",
        "citedIndexes",
        "outOfRange",
        "uncited",
        "problems",
    }


async def test_verify_endpoint_reports_missing_evidence_for_unknown_chunks(
    app: FastAPI, secret: str
) -> None:
    """端点只认语料里的段落：编一个 postId 会被判成「回不到原文」，而不是被信。"""
    bogus = cite(0, post_id=999_999)
    status, payload = await post_verify(
        app, secret, {"answer": "看 [1]。", "citations": [bogus]}
    )

    assert status == 200
    assert payload["verdict"] == "warn"
    assert [item["kind"] for item in payload["problems"]] == [verifier.PROBLEM_UNKNOWN_CHUNK]


async def test_verify_endpoint_rejects_unknown_fields(app: FastAPI, secret: str) -> None:
    """契约是 `extra=forbid`：多传字段要报错，而不是静默忽略。"""
    status, payload = await post_verify(
        app, secret, {"answer": "x", "citations": [], "notAField": 1}
    )

    assert status == 422
    assert "detail" in payload


async def test_verify_endpoint_never_asks_for_a_model(
    app: FastAPI, secret: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """**零模型调用的强断言**：把取模型的三个入口全改成「一取就炸」。

    这比「不配模型也能跑」更强：后者只证明「没配也活着」，
    而这条证明**代码路径上根本没有那一步**。核验是用户随手点得到的按钮，
    它一旦偷偷多花一次模型调用，账单上看到的是「问答变贵了」。
    """
    from app.providers.registry import ProviderRegistry

    def explode(*_: Any, **__: Any) -> Any:
        raise AssertionError("核验不该去取任何模型")

    monkeypatch.setattr(ProviderRegistry, "chat_model", explode)
    monkeypatch.setattr(ProviderRegistry, "embedding_model", explode)
    monkeypatch.setattr(ProviderRegistry, "rerank_model", explode)

    status, payload = await post_verify(
        app, secret, {"answer": "靠的是每天五百字 [1]。", "citations": [cite(0)]}
    )

    assert status == 200, payload
    assert payload["verdict"] in {"ok", "warn"}


async def test_verify_endpoint_accepts_an_empty_request_body(app: FastAPI, secret: str) -> None:
    """两个字段都可缺省：空请求是「没有答案、也没有引用」，不该报错。"""
    status, payload = await post_verify(app, secret, {})

    assert status == 200
    assert payload["verdict"] == "ok"
    assert payload["checked"] == 0
    assert payload["problems"] == []


async def test_verify_does_not_leak_internals(app: FastAPI, secret: str) -> None:
    status, payload = await post_verify(
        app, secret, {"answer": "看 [1]。", "citations": [cite(0)]}
    )

    body = json.dumps(payload, ensure_ascii=False)
    for forbidden in ("8200", "127.0.0.1", "AI_INTERNAL_SECRET", "sk-", "Traceback"):
        assert forbidden not in body, f"响应泄露了内部信息：{forbidden}"


async def test_verifier_agent_name_is_rejected_on_ask_with_a_pointer(
    app: FastAPI, secret: str
) -> None:
    """核验员**不走 `/agent/ask`**：它不产出答案。

    刻意不是 422（名字是对的），也不是「随便生成一段」——
    而是指路到 `/agent/verify`，因为「用错端点」与「名字拼错」是两种不同的错。
    """
    body = json.dumps({"question": "一年写十八万字的方法是什么？", "agent": "verifier"})
    headers = {
        **signed_headers("POST", "/agent/ask", secret=secret, body=body, role="AUTHOR", user_id=1),
        "Content-Type": "application/json",
    }

    response = await call(app, "POST", "/agent/ask", headers=headers, content=body)

    assert response.status_code == 400
    payload = response.json()
    assert "/agent/verify" in payload["message"]
    assert "verifier" in payload["message"]
