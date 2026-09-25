"""写作画像的测试：**只量不写**、样本不足给人话、绝不引用原句。

画像会进提示词，所以这里的断言不只是「算得对」，还有两条不变式：
- 不出现作者的完整句子（模型会照抄，读者一眼看得出来）；
- 样本不够时 `evidenceSufficient=false` 且 `profile` 为空（0 与「没量」是两件事）。
"""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from app.rag.style import (
    StyleSettings,
    build_style_profile,
)


@dataclass(frozen=True)
class _Post:
    """画像只需要 title + plain（与 `app/rag/style.py` 的 PostLike 协议一致）。"""

    title: str
    plain: str


def make_posts(count: int = 10) -> list[_Post]:
    """造若干篇「句短、逗号多、爱用『其实』」的样本：指标应能一眼看出倾向。

    默认 10 篇而非 3 篇：画像有 `min_total_chars=500` 的下限，
    样本太短会直接返回「量不出」—— 那正是另一条测试要验的行为，不该混在这里。
    """
    body = (
        "其实我写得慢。一天五百字，不多。\n"
        "早上写一点，晚上再写一点。其实不写也行，但不写会想。\n"
        "所以我不追热点。热点太快了，我跟不上——那就慢一点。\n"
    )
    return [_Post(title=f"第 {index} 篇：慢一点", plain=body) for index in range(1, count + 1)]


def test_profile_measures_the_obvious_habits() -> None:
    profile = build_style_profile(make_posts())

    assert profile is not None
    assert profile.sample_count == 10
    assert profile.sentence_count > 0
    assert profile.char_count > 500, "三篇样本的字数应当超过下限"
    # 「句短」：中位句长应落在短句阈值内
    assert profile.median_sentence_chars <= 20
    assert profile.short_sentence_ratio > 0.3, "这批样本以短句为主"
    assert profile.clauses_per_100_chars > 2, "样本逗号密度高"
    assert "其实" in profile.transitions, "反复出现的关联词要被量出来"


def test_profile_never_quotes_a_whole_sentence() -> None:
    """画像里不能出现完整原句 —— 出现就会被模型照抄。"""
    posts = make_posts()
    profile = build_style_profile(posts)
    assert profile is not None

    rendered = profile.describe()
    for sentence in ("其实我写得慢。", "一天五百字，不多。", "所以我不追热点。"):
        assert sentence not in rendered, f"画像引用了原句：{sentence}"

    # 反过来：反复出现的短字组应当**在**画像里（那是习惯，不是内容）
    assert profile.common_phrases, "三篇里重复了多次的写法应当被量成字组"
    assert all(3 <= len(phrase) <= 6 for phrase in profile.common_phrases)


def test_phrases_require_repetition() -> None:
    """出现一次的是内容，不是习惯：只有重复到阈值的字组才允许出现。"""
    once = [_Post(title="只有一次", plain="独一无二的说法在这里出现了，但它只出现一次。\n" * 1)]
    profile = build_style_profile(once)
    assert profile is None or "独一无二" not in profile.common_phrases


def test_sample_shortage_returns_none_not_zeros() -> None:
    tiny = [_Post(title="刚开张", plain="今天开始写。\n")]

    assert build_style_profile(tiny) is None, "样本不够时不给画像，而不是给一堆 0"


def test_code_blocks_are_excluded_from_the_metrics() -> None:
    """代码块不是写作风格：留着会把句长与高频词全带偏。"""
    plain = "先说结论：这个超时是连接池的锅。\n" * 40
    with_code = plain + "\n```bash\nredis-cli --latency-history -i 1\n```\n"
    without = [_Post(title="无代码", plain=plain)]
    with_block = [_Post(title="有代码", plain=with_code)]

    base = build_style_profile(without)
    coded = build_style_profile(with_block)
    assert base is not None and coded is not None

    assert "redis" not in " ".join(coded.common_phrases).lower()
    assert coded.char_count == base.char_count, "代码块不应计入字数"


def test_settings_reject_a_repetition_threshold_of_one() -> None:
    """阈值降到 1 就等于「允许引用原句」，必须直接被参数校验拦下。"""
    with pytest.raises(ValueError, match="习惯"):
        StyleSettings(phrase_min_count=1)
    with pytest.raises(ValueError, match="字组长度"):
        StyleSettings(phrase_min=6, phrase_max=3)


def test_settings_limit_how_many_posts_are_read() -> None:
    """`max_samples` 必须真的限制读取篇数，而不是只影响展示。

    构造上要留意：画像有字数下限，因此样本本身要够长 ——
    否则测到的是「样本不足」而不是「取样上限」（第一版就是 5 篇短文，
    拿到 None，差点以为是 max_samples 没生效）。
    """
    long_body = "其实写东西这件事，慢一点更好。一天五百字，不多不少，刚好够想清楚一件事。\n" * 10
    posts = [_Post(title=f"第 {index} 篇", plain=long_body) for index in range(1, 6)]

    profile = build_style_profile(posts, settings=StyleSettings(max_samples=2))

    assert profile is not None
    assert profile.sample_count == 2, "max_samples 必须真的限制读取篇数"


def test_describe_is_readable_chinese() -> None:
    profile = build_style_profile(make_posts())
    assert profile is not None

    text = profile.describe()
    assert "样本：" in text and "句" in text
    assert "字组" in text or "关联词" in text
