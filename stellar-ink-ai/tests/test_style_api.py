"""写作画像接口测试：签名保护、契约形状、「样本不足」是数据不是错误。

画像的算法由 `tests/test_style.py` 覆盖；这里只关心**接口层**：
受内部签名保护、字段与 `WritingStyleResult` 契约一致、作者不存在时不报 500。
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI

from app.api.v1.style import load_corpus
from app.core.internal_auth import InternalRequestVerifier
from app.main import create_app
from app.rag.style import TRANSITION_WORDS
from tests.signing import FIXED_TIMESTAMP_MS, call, load_vector, signed_headers

#: 种子内容包里文章最多的作者（`post.user_id`）
SEED_AUTHOR_ID = 1
#: 种子内容包里没有文章的作者
ABSENT_AUTHOR_ID = 999

PROFILE_FIELDS = {
    "sampleCount",
    "charCount",
    "paragraphCount",
    "sentenceCount",
    "medianSentenceChars",
    "minSentenceChars",
    "maxSentenceChars",
    "shortSentenceRatio",
    "clausesPer100Chars",
    "questionRatio",
    "informalMarkRatio",
    "commonPhrases",
    "transitions",
    "topTags",
}


@pytest.fixture()
def secret() -> str:
    return str(load_vector()["secret"])


@pytest.fixture()
def app(secret: str, monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr("app.core.internal_auth.time.time", lambda: FIXED_TIMESTAMP_MS / 1000)
    load_corpus.cache_clear()
    return create_app(verifier=InternalRequestVerifier(secret))


async def post_style(app: FastAPI, secret: str, payload: dict) -> tuple[int, dict]:
    body = json.dumps(payload, ensure_ascii=False)
    headers = {
        **signed_headers(
            "POST", "/writing/style", secret=secret, body=body, role="AUTHOR", user_id=1
        ),
        "Content-Type": "application/json",
    }
    response = await call(app, "POST", "/writing/style", headers=headers, content=body)
    return response.status_code, response.json()


async def test_style_requires_signature(app: FastAPI) -> None:
    """画像会读作者的文章正文，绝不能匿名触发。"""
    response = await call(app, "POST", "/writing/style", json={"authorId": SEED_AUTHOR_ID})

    assert response.status_code == 401


async def test_style_returns_the_profile_contract(app: FastAPI, secret: str) -> None:
    status, payload = await post_style(app, secret, {"authorId": SEED_AUTHOR_ID})

    assert status == 200
    assert set(payload) == {"authorId", "evidenceSufficient", "profile", "notes"}
    assert payload["authorId"] == SEED_AUTHOR_ID
    assert payload["evidenceSufficient"] is True
    assert set(payload["profile"]) == PROFILE_FIELDS
    assert payload["notes"], "口径说明要透出：前端要能告诉作者这些数字怎么来的"


async def test_style_measures_the_seed_corpus(app: FastAPI, secret: str) -> None:
    """种子语料里作者 1 写得够多：必须能量出画像，而不是「样本不足」。

    下面的数字是**实测值**（不是算出来的期望）：换了语料或改了统计口径就会红，
    这正是要的效果 —— 静默变化的指标比错误的指标更难发现。
    """
    _, payload = await post_style(app, secret, {"authorId": SEED_AUTHOR_ID})
    profile = payload["profile"]

    assert profile["sampleCount"] == 20, "默认最多取 20 篇"
    assert profile["charCount"] == 3071
    assert profile["sentenceCount"] == 124
    assert profile["medianSentenceChars"] == 24.0
    # 短句占比与问句占比是比例：必须落在 [0,1]
    assert 0 <= profile["shortSentenceRatio"] <= 1
    assert 0 <= profile["questionRatio"] <= 1

    # 关联词取的是「出现次数最多的前 5 个」：因此榜首必然是真出现过的词。
    # 断言方式刻意用「独立数一遍」而不是写死某个词 —— 写死第一个版本时我猜的是「其实」，
    # 而它在语料里只出现 1 次，根本进不了前 5（终端的乱码让我误判了真实内容）。
    # 取样范围必须与接口一致（**只数被取中的那 20 篇**）：作者 1 共有 25 篇，
    # 拿全部 25 篇去数会得到另一份排名，测试就会以一种「差一位」的形态红给你看。
    sampled = [post for post in load_corpus() if post.author_id == 1][:20]
    corpus = "".join(post.title + "\n" + post.plain for post in sampled)
    counted = sorted(
        ((word, corpus.count(word)) for word in TRANSITION_WORDS if corpus.count(word)),
        key=lambda item: (-item[1], item[0]),
    )
    assert profile["transitions"] == [word for word, _ in counted[:5]]


async def test_max_samples_really_limits_the_sample(app: FastAPI, secret: str) -> None:
    """`maxSamples` 必须真的少读，而不是只影响展示。"""
    _, payload = await post_style(app, secret, {"authorId": SEED_AUTHOR_ID, "maxSamples": 6})

    assert payload["profile"]["sampleCount"] == 6


async def test_common_phrases_are_habits_not_quotes(app: FastAPI, secret: str) -> None:
    """画像进提示词，因此**不允许出现完整原句**（模型会照抄，读者一眼看得出来）。"""
    _, payload = await post_style(app, secret, {"authorId": SEED_AUTHOR_ID})
    corpus = load_corpus()

    phrases = payload["profile"]["commonPhrases"]
    assert phrases, "作者 1 的文章里应当有反复出现的字组"
    for phrase in phrases:
        assert 3 <= len(phrase) <= 6, f"字组长度超出约定：{phrase}"
        # 每条字组都不能是一整句（原句会带标点）
        assert not any(mark in phrase for mark in "。！？，、；："), f"字组里混进了标点：{phrase}"
        # 也不能等于任何一篇的完整句子
        for post in corpus:
            for sentence in post.plain.replace("\n", "。").split("。"):
                assert phrase != sentence.strip(), f"字组等于原句：{phrase}"


async def test_absent_author_is_not_an_error(app: FastAPI, secret: str) -> None:
    """没有任何文章的作者：`evidenceSufficient=false` 且 profile 为空。

    **不是 404、也不是一堆 0** —— 0 与「没量」是两件事，后者要能显示成一句人话。
    """
    status, payload = await post_style(app, secret, {"authorId": ABSENT_AUTHOR_ID})

    assert status == 200
    assert payload["evidenceSufficient"] is False
    assert payload["profile"] is None
    assert "画像" in payload["notes"], "要说清是「量不出画像」，而不是别的失败"


async def test_author_with_too_little_content_gets_a_readable_reason(
    app: FastAPI, secret: str
) -> None:
    """样本不足时提示要带**实际篇数与字数** —— 「不足」两个字没有可操作性。

    用「同一作者、只取很少几篇」来构造，而不是找一个写得少的作者：
    种子里作者 4 虽然只有 2 篇，但那两篇合计 569 字，恰好过了下限 ——
    依赖真实语料的边界值来测边界，测的是语料，不是代码。
    """
    status, payload = await post_style(app, secret, {"authorId": SEED_AUTHOR_ID, "maxSamples": 3})

    assert status == 200
    assert payload["evidenceSufficient"] is False
    assert payload["profile"] is None
    assert "3 篇" in payload["notes"], f"提示里要有实际篇数：{payload['notes']}"
    assert "字" in payload["notes"], "也要给出字数口径，否则作者不知道差多少"


async def test_max_samples_is_capped_by_the_contract(app: FastAPI, secret: str) -> None:
    status, payload = await post_style(app, secret, {"authorId": SEED_AUTHOR_ID, "maxSamples": 999})

    assert status == 422, "超过上限要在契约层拦下，不能悄悄按最大值跑"
    assert "detail" in payload


async def test_style_does_not_leak_internals(app: FastAPI, secret: str) -> None:
    _, payload = await post_style(app, secret, {"authorId": SEED_AUTHOR_ID})

    body = json.dumps(payload, ensure_ascii=False)
    for forbidden in ("8200", "127.0.0.1", "AI_INTERNAL_SECRET", "apiKey", "sk-"):
        assert forbidden not in body, f"响应泄露了内部信息：{forbidden}"
