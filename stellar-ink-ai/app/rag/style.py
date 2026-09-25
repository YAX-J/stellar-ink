"""写作风格画像：从作者**已发布**的文章里量出可复述的习惯。

这个模块是 E 阶段的记忆底座：E2 的只读 Agent、Copilot 的润色都要「这个作者平时怎么说话」，
而这类上下文必须是**可解释、可删除、可重算**的统计量，不是模型编出来的人设。

三条硬边界（写在这里免得后人放宽）：

1. **只读已发表内容**。画像只吃调用方给的文章列表（Java 侧只传 `status=published` 的正文），
   草稿永不入内 —— 未发表的内容一旦被画像引用，就等于把它泄露到了提示词里。
2. **不引用原句**。画像只输出统计量（句长、标点频率、高频词）与**反复出现**的字组，
   绝不摘录整句。原因有两个：中文里「作者的一句原话」常常就是最私人的部分；
   而且把原句塞进提示词，模型下一轮会照抄，读者一眼就看出来。
   因此 `common_phrases` 的候选必须**至少出现 3 次**：重复三次以上的是习惯，出现一次的是内容。
3. **先用后算、随时可弃**。画像是派生数据，删掉文章重算就变；它不参与检索、不进引用、
   不落任何业务表（当前由接口按请求现算，见 `app/api/v1/writing.py`）。

为什么不用分词库：种子语料是中文散文，装一个中文分词器只为数句读不划算，
而本模块的每个指标都能用字符级扫描算准（句读切分 + 拉丁词边界）。
真需要词性/实体时再引入分词，那时也应该把画像换成「有模型参与」的版本并重新标定。
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Protocol

#: 句末标点（中英并集）：这些字符之后就是一句的边界
SENTENCE_ENDINGS = "。！？!?…"
#: 句中停顿：用来量「逗号密度」，也是中文长句的主要标志
CLAUSE_MARKS = "，,、；;：:"
#: 非正式标点：破折号、省略号、波浪号等带情绪的写法
INFORMAL_MARKS = "——～~…"
#: 问句判定用的结尾
QUESTION_ENDINGS = "？?"

#: 常见关联词/口头禅候选（中文写作里最常反复出现的连接词）。
#: 用固定表而不是「高频二字词」：后者在中文里会把「我们」「这个」这类噪声排到前面，
#: 而关联词表小、可读、可增删，出问题时人能一眼看懂。
TRANSITION_WORDS = (
    "其实",
    "所以",
    "但是",
    "不过",
    "于是",
    "然后",
    "因为",
    "如果",
    "也许",
    "大概",
    "后来",
    "最后",
    "当时",
    "现在",
    "之前",
    "接着",
    "反而",
    "而且",
    "虽然",
    "至于",
    "我猜",
    "我觉得",
    "说实话",
    "说到底",
    "换句话说",
    "与此同时",
    "结果",
    "毕竟",
)
#: 拉丁关联词（作者混写英文时也算）
LATIN_TRANSITIONS = ("however", "because", "therefore", "but", "so", "actually")

#: 代码/标记噪声：分析前剔除，否则 `redis-cli` 这类会把「句长」和「高频词」带偏
_CODE_FENCE = re.compile(r"```.*?```", re.DOTALL)
_INLINE_CODE = re.compile(r"`[^`]*`")
_URL = re.compile(r"https?://\S+")
_MARKDOWN_MARK = re.compile(r"^[#>\-\*\s]+", re.MULTILINE)
_LATIN_WORD = re.compile(r"[A-Za-z0-9_]+")
_CJK_CHAR = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
#: 一个「可断句的连续片段」：非空白、非换行
_RUN = re.compile(r"[^\s]+")


class PostLike(Protocol):
    """画像只需要「标题 + 正文」，不依赖种子脚本或数据库模型。"""

    @property
    def title(self) -> str: ...

    @property
    def plain(self) -> str: ...


@dataclass(frozen=True, slots=True)
class StyleSettings:
    """画像的取样口径。改这些值会改变数字，所以它们必须显式、可打印。"""

    #: 最多分析多少篇（按调用方给的顺序取）
    max_samples: int = 20
    #: 样本总字数低于它时**不给画像**：两三百字算出来的「习惯」全是噪声
    min_total_chars: int = 500
    #: 高频字组的长度区间（字符数）
    phrase_min: int = 3
    phrase_max: int = 6
    #: 字组至少出现多少次才算「习惯」（见模块 docstring 第 2 条）
    phrase_min_count: int = 3
    #: 保留几个字组 / 几个关联词
    phrase_top: int = 5
    transition_top: int = 5
    #: 短句阈值：不超过它算短句（中文里 15 字以内读起来是短促的）
    short_sentence_chars: int = 15

    def __post_init__(self) -> None:
        if self.max_samples < 1:
            raise ValueError("max_samples 必须为正")
        if self.phrase_min < 2 or self.phrase_max < self.phrase_min:
            raise ValueError("字组长度区间不合法（phrase_max 不得小于 phrase_min，且至少为 2）")
        if self.phrase_min_count < 2:
            raise ValueError("phrase_min_count 至少为 2：出现一次的是内容，不是习惯")
        if self.short_sentence_chars < 1:
            raise ValueError("short_sentence_chars 必须为正")


@dataclass(frozen=True, slots=True)
class StyleProfile:
    """量出来的写作习惯。字段名即 JSON 名（驼峰由契约层转换）。"""

    sample_count: int
    char_count: int
    paragraph_count: int
    sentence_count: int
    #: 句子长度的中位数（比平均数稳：一篇长句不会把「典型句长」带跑）
    median_sentence_chars: float
    min_sentence_chars: int
    max_sentence_chars: int
    short_sentence_ratio: float
    clauses_per_100_chars: float
    question_ratio: float
    informal_mark_ratio: float
    #: 反复出现的字组：习惯的痕迹（至少出现 phrase_min_count 次）
    common_phrases: list[str] = field(default_factory=list)
    #: 常用关联词，按出现次数排序
    transitions: list[str] = field(default_factory=list)
    #: 最常用的标签（来自调用方传入的元数据，不是正文）
    top_tags: list[str] = field(default_factory=list)

    def describe(self) -> str:
        """渲染成给模型看的一段中文说明。

        **只说数字与习惯，不贴原句** —— 这是本模块最重要的一条不变式（模块 docstring 第 2 条）。
        """
        parts = [
            f"样本：{self.sample_count} 篇 / {self.char_count} 字",
            f"句子：{self.sentence_count} 句，中位 {self.median_sentence_chars:g} 字"
            f"（最短 {self.min_sentence_chars} / 最长 {self.max_sentence_chars}）",
            f"短句占比 {self.short_sentence_ratio:.0%}",
            f"逗号密度 {self.clauses_per_100_chars:.1f} 次/百字",
        ]
        if self.question_ratio > 0.02:
            parts.append(f"问句占比 {self.question_ratio:.0%}")
        if self.informal_mark_ratio > 0.002:
            parts.append(f"破折号/省略号密度 {self.informal_mark_ratio:.1%}")
        if self.transitions:
            parts.append("常用关联词：" + "、".join(self.transitions))
        if self.common_phrases:
            parts.append("反复出现的字组：" + "、".join(self.common_phrases))
        if self.top_tags:
            parts.append("常写主题：" + "、".join(self.top_tags))
        return "；".join(parts)


@dataclass(frozen=True, slots=True)
class _Sample:
    """一篇用于画像的文章（已剔除代码块与链接）。"""

    chars: int
    sentences: list[str]
    paragraphs: int
    tags: list[str]


def build_style_profile(
    samples: Sequence[PostLike],
    *,
    tags_per_title: Sequence[Sequence[str]] | None = None,
    settings: StyleSettings | None = None,
) -> StyleProfile | None:
    """从最多 `max_samples` 篇里量出画像；样本不足时返回 `None`。

    **返回 None 而不是抛异常**：作者只写过两三百字是正常状态，此时前端要看到
    「样本还不够」这句人话，而不是一个 500。调用方负责把 `None` 翻译成提示。
    """
    config = settings or StyleSettings()
    parsed = [
        _parse_sample(post, tags_per_title[index] if tags_per_title else [])
        for index, post in enumerate(samples[: config.max_samples])
    ]
    total_chars = sum(sample.chars for sample in parsed)
    if total_chars < config.min_total_chars:
        return None

    sentences = [sentence for sample in parsed for sentence in sample.sentences]
    if not sentences:
        return None

    lengths = sorted(len(sentence) for sentence in sentences)
    joined = "".join(sentence for sentence in sentences)
    clause_count = sum(1 for char in joined if char in CLAUSE_MARKS)
    question_count = sum(1 for sentence in sentences if sentence.endswith(tuple(QUESTION_ENDINGS)))
    informal_count = sum(1 for char in joined if char in INFORMAL_MARKS)

    return StyleProfile(
        sample_count=len(parsed),
        char_count=total_chars,
        paragraph_count=sum(sample.paragraphs for sample in parsed),
        sentence_count=len(sentences),
        median_sentence_chars=_median(lengths),
        min_sentence_chars=lengths[0],
        max_sentence_chars=lengths[-1],
        short_sentence_ratio=_ratio(
            sum(1 for length in lengths if length <= config.short_sentence_chars), len(lengths)
        ),
        clauses_per_100_chars=round(clause_count * 100 / max(total_chars, 1), 2),
        question_ratio=_ratio(question_count, len(sentences)),
        informal_mark_ratio=round(informal_count / max(len(joined), 1), 4),
        common_phrases=_common_phrases(joined, config),
        transitions=_common_transitions(joined, config),
        top_tags=_top_tags(parsed, config),
    )


def _parse_sample(post: PostLike, tags: Sequence[str]) -> _Sample:
    raw = f"{post.title}\n{post.plain}"
    text = _clean(raw)
    return _Sample(
        chars=_count_units(text),
        sentences=_split_sentences(text),
        paragraphs=sum(1 for line in text.splitlines() if line.strip()),
        tags=[tag.strip() for tag in tags if tag.strip()],
    )


def _clean(text: str) -> str:
    """去掉代码块、行内代码与链接：它们不是「写作风格」，留着会污染全部指标。"""
    text = _CODE_FENCE.sub(" ", text)
    text = _INLINE_CODE.sub(" ", text)
    text = _URL.sub(" ", text)
    return _MARKDOWN_MARK.sub("", text)


def _split_sentences(text: str) -> list[str]:
    """按句末标点切句，标点留在前一句末尾（问句判定要用它）。"""
    sentences: list[str] = []
    buffer = ""
    for char in text:
        if char.isspace():
            # 换行/空格不切断句读，但也不进句子（避免把缩进算进句长）
            buffer = buffer.rstrip()
            continue
        buffer += char
        if char in SENTENCE_ENDINGS:
            if buffer.strip():
                sentences.append(buffer.strip())
            buffer = ""
    if buffer.strip():
        sentences.append(buffer.strip())
    return sentences


def _count_units(text: str) -> int:
    """长度口径：**中日韩字符按字、拉丁/数字按词**。

    为什么不用 `len()`：`Redis` 五个字符在阅读上是一个词，而中文一个字就是一个字；
    混着算会让「技术文」的句长虚高，而技术文恰好是本仓库的主要语料。
    这个口径是画像的一部分，改它必须同步改测试里的期望值。
    """
    cjk = len(_CJK_CHAR.findall(text))
    latin = len(_LATIN_WORD.findall(text))
    return cjk + latin


def _common_phrases(text: str, config: StyleSettings) -> list[str]:
    """反复出现的字组（连续中日韩字符），按「出现次数 × 长度」排序。

    只在**CJK 连续段**内取：跨标点取字组会得到「我们，所以」这种不成词的东西。
    """
    counts: dict[str, int] = {}
    for run in _CJK_RUNS.findall(text):
        for size in range(config.phrase_min, config.phrase_max + 1):
            for start in range(len(run) - size + 1):
                gram = run[start : start + size]
                counts[gram] = counts.get(gram, 0) + 1
    frequent = [(gram, count) for gram, count in counts.items() if count >= config.phrase_min_count]
    # 排序：先按出现次数，再按长度（同频时长的那条更像习惯），最后按字组本身保证结果稳定
    frequent.sort(key=lambda item: (-item[1], -len(item[0]), item[0]))
    return [gram for gram, _ in frequent[: config.phrase_top]]


def _common_transitions(text: str, config: StyleSettings) -> list[str]:
    lowered = text.lower()
    scored = [(word, lowered.count(word.lower())) for word in TRANSITION_WORDS]
    scored.extend((word, lowered.count(word)) for word in LATIN_TRANSITIONS)
    hits = [(word, count) for word, count in scored if count > 0]
    hits.sort(key=lambda item: (-item[1], item[0]))
    return [word for word, _ in hits[: config.transition_top]]


def _top_tags(samples: Sequence[_Sample], config: StyleSettings) -> list[str]:
    counts: dict[str, int] = {}
    for sample in samples:
        for tag in sample.tags:
            counts[tag] = counts.get(tag, 0) + 1
    ranked = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    return [tag for tag, _ in ranked[: config.phrase_top]]


def _median(sorted_values: Sequence[int]) -> float:
    size = len(sorted_values)
    if size == 0:
        return 0.0
    middle = size // 2
    if size % 2:
        return float(sorted_values[middle])
    return (sorted_values[middle - 1] + sorted_values[middle]) / 2


def _ratio(part: int, whole: int) -> float:
    return round(part / whole, 4) if whole else 0.0


#: 连续中日韩片段（取字组用，放在末尾便于阅读上面的逻辑）
_CJK_RUNS = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]+")
