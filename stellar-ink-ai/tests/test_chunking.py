"""文章切块：边界、锚点、父子关系、幂等。

这些用例是检索质量的**下限保障**：切块错了，后面再好的向量模型与重排也救不回来，
而它的错误往往表现为「召回率莫名偏低」，极难在产品层察觉。
因此这里对边界条件（中文标点、空段、超长段、重复段、代码块、无标题）逐个固定行为。
"""

from __future__ import annotations

from datetime import datetime

import pytest

from app.rag.chunking import (
    ChunkingConfig,
    PostDocument,
    chunk_document,
    content_version,
    normalize,
)

ARTICLE = """星笺把文章比作星辰。

## 一、为什么是星辰

夜空中的每一个坐标都对应一篇文章，读者按坐标回访。
这样做的原因是让写作有位置感，而不是流式的信息堆叠。

## 二、怎么实现

### 2.1 坐标生成

按发布时间与标签散列出一个稳定的坐标，保证同一篇文章永远在同一位置。

### 2.2 索引

只索引已发布内容。草稿不进入公共索引，这是硬约束。

```python
# 下面这行不是标题
## 也不是
```

## 三、结论

先把「有证据的回答」做扎实，再谈更复杂的能力。
"""


def make_doc(content: str = ARTICLE, **overrides: object) -> PostDocument:
    fields: dict[str, object] = {
        "post_id": 12,
        "title": "把文章写成星图",
        "content": content,
        "tags": ["写作", "RAG"],
        "published_at": datetime(2026, 9, 24, 21, 0),
        "author_id": 1,
    }
    fields.update(overrides)
    return PostDocument(**fields)  # type: ignore[arg-type]


def test_normalize_only_changes_line_endings_and_edges() -> None:
    """只统一换行与首尾空白：引用片段要能直接展示，不能改写标点。"""
    assert normalize("a\r\nb\rc  ") == "a\nb\nc"
    assert normalize("") == ""
    assert normalize("  星笺  ") == "星笺"


def test_empty_content_yields_no_chunks() -> None:
    assert chunk_document(make_doc(content="")) == []
    assert chunk_document(make_doc(content="   \n\n  ")) == []


def test_chunks_are_deterministic_and_ids_stable() -> None:
    """同一输入两次切块必须完全一致 —— 重索引幂等的前提。"""
    first = chunk_document(make_doc())
    second = chunk_document(make_doc())

    assert [chunk.chunk_id for chunk in first] == [chunk.chunk_id for chunk in second]
    assert [chunk.to_payload() for chunk in first] == [chunk.to_payload() for chunk in second]


def test_version_changes_only_when_content_changes() -> None:
    base = content_version(make_doc())
    assert base == content_version(make_doc())

    # 标题、正文、标签任一变化都该换版本；改作者或时间不该换（不影响检索内容）
    assert content_version(make_doc(title="另一个标题")) != base
    assert content_version(make_doc(content=ARTICLE + "\n补充一句。")) != base
    assert content_version(make_doc(tags=["写作"])) != base
    assert content_version(make_doc(author_id=99)) == base


def test_sections_become_parents_with_heading_path() -> None:
    chunks = chunk_document(make_doc())
    parents = [chunk for chunk in chunks if chunk.chunk_type == "parent"]

    assert len(parents) >= 4
    paths = [parent.heading_path for parent in parents]
    # 标题路径包含层级，便于引用时告诉读者「这话出自哪一节」
    assert any("一、为什么是星辰" in path for path in paths)
    assert any("二、怎么实现" in path and "2.1 坐标生成" in path for path in paths)
    # 父块索引连续
    assert [parent.chunk_index for parent in parents] == list(range(len(parents)))


def test_headings_inside_code_fence_are_not_sections() -> None:
    """代码块里的 `## 也不是` 不能被当成章节标题。"""
    chunks = chunk_document(make_doc())

    assert not any("也不是" in chunk.heading_path for chunk in chunks)
    # 代码块内容仍然被索引（它是文章的一部分）
    assert any("不是标题" in chunk.text for chunk in chunks)


def test_children_reference_their_parent_and_stay_inside_it() -> None:
    chunks = chunk_document(make_doc())
    parents = [chunk for chunk in chunks if chunk.chunk_type == "parent"]
    children = [chunk for chunk in chunks if chunk.chunk_type == "child"]

    assert children, "应当切出子块"
    for child in children:
        assert child.parent_index is not None
        parent = parents[child.parent_index]
        assert child.heading_path == parent.heading_path, "子块必须继承父块的标题路径"
        # 子块文本必须能在父块里找到（不跨章节、不截断到别的章节）
        assert child.text in parent.text
        # 子块锚点必须落在父块范围内
        assert parent.char_start <= child.char_start < child.char_end <= parent.char_end


def test_children_are_numbered_continuously() -> None:
    children = [chunk for chunk in chunk_document(make_doc()) if chunk.chunk_type == "child"]

    assert [child.chunk_index for child in children] == list(range(len(children)))


def test_short_sentences_do_not_produce_tiny_chunks() -> None:
    """中文技术文一句话常只有 8~15 字：「见句号就切」会把目标块长切成一堆碎块。

    这里用大量短句，断言块长大多接近目标值（而不是贴着最短句长）。
    """
    text = "星笺把文章比作星辰。" * 60  # 每句 9 字
    cfg = ChunkingConfig(child_size=200, child_overlap=20, min_child_size=50)

    children = [
        chunk
        for chunk in chunk_document(make_doc(content=text), cfg)
        if chunk.chunk_type == "child"
    ]

    assert children
    average = sum(len(child.text) for child in children) / len(children)
    assert average >= 120, f"平均块长 {average:.0f} 太短，说明短句被过度切分"
    # 除最后一块外，不应出现远小于目标的碎块
    assert all(len(child.text) >= 50 for child in children[:-1])


def test_child_size_respects_config_with_overlap() -> None:
    cfg = ChunkingConfig(child_size=120, child_overlap=30, min_child_size=20)
    children = [chunk for chunk in chunk_document(make_doc(), cfg) if chunk.chunk_type == "child"]

    assert children
    # 允许略超（切在句子边界上），但不能离谱
    assert all(len(child.text) <= 120 + 80 for child in children)


def test_overlap_makes_adjacent_children_share_text() -> None:
    """重叠是为了跨块语义连续：相邻子块应有公共片段。"""
    long_paragraph = "星笺把文章比作星辰，" * 60
    cfg = ChunkingConfig(child_size=200, child_overlap=50, min_child_size=40)

    children = [
        chunk
        for chunk in chunk_document(make_doc(content=long_paragraph), cfg)
        if chunk.chunk_type == "child"
    ]

    assert len(children) >= 3
    tail = children[0].text[-20:]
    assert tail in children[1].text, "相邻子块应因重叠而共享片段"


def test_long_paragraph_without_punctuation_is_hard_split() -> None:
    """没有任何标点的超长段也必须能切开，不能产生一个巨大的块。"""
    blob = "星" * 1000
    cfg = ChunkingConfig(child_size=200, child_overlap=20, min_child_size=50)

    children = [
        chunk
        for chunk in chunk_document(make_doc(content=blob), cfg)
        if chunk.chunk_type == "child"
    ]

    assert len(children) >= 4
    assert all(len(child.text) <= 200 for child in children), "硬切必须严格受 child_size 限制"


def test_article_without_headings_still_chunks() -> None:
    text = "星笺把文章比作星辰。" * 40

    chunks = chunk_document(make_doc(content=text))
    parents = [chunk for chunk in chunks if chunk.chunk_type == "parent"]

    assert len(parents) == 1
    assert parents[0].heading_path == ""
    assert any(chunk.chunk_type == "child" for chunk in chunks)


def test_duplicate_paragraphs_are_kept_as_separate_chunks() -> None:
    """重复段落不能被去重：它们位置不同，各自都要能被引用。"""
    text = "## 反复\n\n星笺把文章比作星辰。\n\n星笺把文章比作星辰。\n\n星笺把文章比作星辰。"

    children = [
        chunk for chunk in chunk_document(make_doc(content=text)) if chunk.chunk_type == "child"
    ]

    assert len(children) >= 1
    # 段落相同但位置不同：锚点必须不同，否则引用会指向错的地方
    starts = [child.char_start for child in children]
    assert len(set(starts)) == len(starts)


def test_char_anchors_point_into_normalized_text() -> None:
    """锚点必须能在清洗后的正文里原样取回文本 —— 前端要靠它高亮原文。"""
    doc = make_doc()
    normalized = normalize(doc.content)

    for chunk in chunk_document(doc):
        assert normalized[chunk.char_start : chunk.char_end].strip() == chunk.text


def test_chunk_payload_has_camel_case_keys_and_no_secrets() -> None:
    """payload 直接进 Qdrant：键名驼峰（与 Java 契约一致），且不含任何密钥。"""
    payload = chunk_document(make_doc())[0].to_payload()

    assert set(payload) >= {"chunkId", "postId", "chunkType", "text", "contentHash"}
    assert all("_" not in key for key in payload)
    assert "secret" not in str(payload).lower()


@pytest.mark.parametrize(
    "config",
    [
        ChunkingConfig(child_size=10),
        ChunkingConfig(child_size=200, child_overlap=-1),
        ChunkingConfig(child_size=200, child_overlap=200),
        ChunkingConfig(child_size=200, child_overlap=100, min_child_size=0),
        ChunkingConfig(child_size=200, min_child_size=500),
    ],
)
def test_invalid_config_is_rejected_loudly(config: ChunkingConfig) -> None:
    """参数非法要当场报错：重叠 ≥ 块长会死循环，必须挡在配置层。"""
    with pytest.raises(ValueError):
        chunk_document(make_doc(), config)


def test_english_article_splits_on_sentence_end() -> None:
    text = "## Overview\n\n" + "This is a sentence about stellar ink. " * 20

    children = [
        chunk
        for chunk in chunk_document(make_doc(content=text), ChunkingConfig(child_size=150))
        if chunk.chunk_type == "child"
    ]

    assert len(children) >= 2
    # 英文句末应当被识别为边界：多数块以句号结尾
    assert sum(1 for child in children if child.text.endswith(".")) >= len(children) - 1
