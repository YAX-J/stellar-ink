"""「引用能不能回到原文」的**共享词汇表**（A1/A2 抽出来的公共口径）。

这个模块只有一件事：把「一段文字真的出现在另一段文字里吗」判成**同一个答案**。
它被三处在用，而且它们必须给出一致的判断：

- `app/rag/wiki.py`：主张的 `quote` 必须逐字来自它标注的段落，否则这条主张不成立；
- `app/agents/verifier.py`：答案里每条 citation 的片段必须真的出现在它标注的原文里；
- 将来的语义核验：同样要以「片段确实存在」为前提，而不是先信模型。

三个函数分别是这条判断的三块积木：

1. `chunk_text()` —— **证据原文**是哪一份。刻意不是 `IndexedChunk.text`：那是检索用文本
   （标题 + 片段），拿它当证据来源的话，引用标题也能通过校验，而标题不是「这一段的原文」。
2. `normalize_evidence()` —— 比对前怎么规范化。模型常把换行、多空格写得与原文不一致，
   那不是「引用不实」；但**只去空白，不做同义改写/繁简转换**：把「不改写」这条红线
   留在原样比对上，否则「逐字来自原文」就成了一句空话。
3. `evidence_contains()` —— 规范化后的子串判定。它同时负责下限（`MIN_EVIDENCE_CHARS`）：
   一个词的「引用」证明不了任何事，宁可不判，也不要给一个看起来很确定的结论。

⚠️ 为什么不让每一处自己写一遍 `re.sub(r"\\s+", "", text)`：这类「看起来一样的判断」一旦
分叉，现象是「同样的引用在 Wiki 里通过、在核验器里被判不实」——两边都「有代码有注释」，
查起来只能靠对比两个正则。
"""

from __future__ import annotations

import re

from app.rag.pipeline import IndexedChunk

#: 证据片段的下限：短于这个长度不做判定（一个词证明不了任何事）。
#: 取值与 `app/rag/wiki.py` 的 `MIN_QUOTE_CHARS` 一致 —— 两个出口对「多短算太短」必须同口径。
MIN_EVIDENCE_CHARS = 4


def chunk_text(chunk: IndexedChunk) -> str:
    """段落原文：优先用 payload 里的 `text`（切块时的那份原文），退回检索文本。

    为什么不直接用 `IndexedChunk.text`：那是**检索用文本**（标题 + 片段）。
    拿它当证据来源的话，模型引用标题也能通过校验 —— 而标题并不是「这一段的原文」。
    """
    payload_text = chunk.payload.get("text")
    if isinstance(payload_text, str) and payload_text.strip():
        return payload_text
    return chunk.text


def normalize_evidence(text: str) -> str:
    """规范化空白后再比对：模型常把换行 / 多空格写得不一致，那不是「引用不实」。

    只去空白：**不做同义改写、不做繁简转换、不做标点归一** ——
    「逐字来自原文」这条红线要留在原样比对上。
    """
    return re.sub(r"\s+", "", text or "")


def evidence_contains(snippet: str, source: str, *, min_chars: int = MIN_EVIDENCE_CHARS) -> bool:
    """`snippet` 是否真的出现在 `source` 里（规范化空白后按子串判定）。

    返回 False 有**两种**原因（太短 / 真的找不到），调用方若要区分，先自己看长度 ——
    这里刻意合并：两者的处置一样（「这条引用没有被观察到」），
    而分开只会让每个调用方都写一遍同样的一分为二。
    """
    needle = normalize_evidence(str(snippet or "").strip())
    if len(needle) < min_chars:
        return False
    return needle in normalize_evidence(source)
