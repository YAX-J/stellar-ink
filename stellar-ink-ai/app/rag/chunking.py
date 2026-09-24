"""文章切块的纯函数实现：父块 + 子块、锚点、内容哈希。

为什么先做切块而不是先接 Qdrant：切块是检索质量的**上限**——切错了再好的向量模型也救不回来；
而它是纯函数，可以用固定用例反复回归，不需要任何外部依赖。等 Qdrant 连通后直接灌进去即可。

分层（父块用于「喂给模型的上下文」，子块用于「召回的最小单位」）：
- 子块（child）：小、准，命中后能定位到具体段落；
- 父块（parent）：包含整个 `##` 章节，命中子块时把它所在父块一起取出，
  避免「半句话进模型」。
这正是 roadmap 里 context-aware chunking 的最小可用形态。

不做的取舍（明确写下来，避免以后重复讨论）：
- 不做语义切分（模型判定边界）：贵且不可复现，先证明规则切分够用；
- 不做跨文章合并：那是 GraphRAG 的事（E 阶段）；
- 不删空白与标点：原文片段要能直接展示给用户，改写内容会让引用对不上原页面。
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from datetime import datetime

#: 默认参数：按中文技术文章的经验值取，允许调用方覆盖（评测台会对比不同参数）
DEFAULT_CHILD_SIZE = 320
DEFAULT_CHILD_OVERLAP = 60
DEFAULT_MIN_CHILD_SIZE = 40

#: 章节标题：`## 二、方案` / `## 方案` / `# 大标题`
_HEADING = re.compile(r"^(#{1,6})\s+(.*)$")

#: 段落分隔：空行（容忍行内空格）
_BLANK_LINE = re.compile(r"\n\s*\n")

#: 句子结束符：中文标点与西文标点都要算，否则中文长段会切不开
_SENTENCE_END = re.compile(r"(?<=[。！？；!?;])|(?<=\.)\s+")

#: 代码块围栏
_FENCE = re.compile(r"^\s*```")


@dataclass(frozen=True, slots=True)
class Chunk:
    """一个可索引片段。

    `chunk_index` 在**整篇文章内**连续（父块与子块各自编号），引用时用它定位；
    `char_start/char_end` 指向清洗后正文的偏移，便于前端高亮原文。
    """

    chunk_id: str
    post_id: int
    chunk_type: str  # "parent" | "child"
    chunk_index: int
    parent_index: int | None
    heading_path: str
    text: str
    char_start: int
    char_end: int
    content_hash: str
    version: str

    def to_payload(self) -> dict[str, object]:
        """转成 Qdrant payload 的键值形态（键名与 Java/Python 契约保持驼峰）。"""
        return {
            "chunkId": self.chunk_id,
            "postId": self.post_id,
            "chunkType": self.chunk_type,
            "chunkIndex": self.chunk_index,
            "parentIndex": self.parent_index,
            "headingPath": self.heading_path,
            "text": self.text,
            "charStart": self.char_start,
            "charEnd": self.char_end,
            "contentHash": self.content_hash,
            "version": self.version,
        }


@dataclass(slots=True)
class ChunkingConfig:
    """切块参数：评测台要能逐项对比，因此全部显式可配。"""

    child_size: int = DEFAULT_CHILD_SIZE
    child_overlap: int = DEFAULT_CHILD_OVERLAP
    min_child_size: int = DEFAULT_MIN_CHILD_SIZE

    def validate(self) -> None:
        if self.child_size < 50:
            raise ValueError("child_size 太小（至少 50），会切出无法提供语义的碎片")
        if self.child_overlap < 0:
            raise ValueError("child_overlap 不能为负")
        if self.child_overlap >= self.child_size:
            # 重叠大于等于块长会导致死循环（每次前进 0 或负距离）
            raise ValueError("child_overlap 必须小于 child_size")
        if self.min_child_size <= 0 or self.min_child_size > self.child_size:
            raise ValueError("min_child_size 必须在 (0, child_size] 之间")


@dataclass(slots=True)
class PostDocument:
    """待索引的文章：只带索引需要的字段，不掺业务字段。"""

    post_id: int
    title: str
    content: str
    tags: list[str] = field(default_factory=list)
    published_at: datetime | None = None
    author_id: int | None = None

    def to_metadata(self) -> dict[str, object]:
        """文章级元数据：写进每个 chunk 的 payload，检索时按它过滤/展示。"""
        return {
            "postId": self.post_id,
            "title": self.title,
            "tags": list(self.tags),
            "publishedAt": self.published_at.isoformat() if self.published_at else None,
            "authorId": self.author_id,
            # 检索时用字符串比较即可按年份/月份过滤（比时间戳更直观）
            "publishedYear": self.published_at.year if self.published_at else None,
        }


def normalize(text: str) -> str:
    """清洗：统一换行、去掉首尾空白。

    刻意**不动**标点与字间空格：引用片段要能直接展示给用户，
    任何改写都会让「引用」与原文页面对不上。
    """
    if not text:
        return ""
    unified = text.replace("\r\n", "\n").replace("\r", "\n")
    return unified.strip()


def content_version(doc: PostDocument) -> str:
    """文章版本：标题 + 正文 + 标签的哈希前 16 位。

    用它做索引幂等的判据：内容没变就跳过重建，变了才重算向量。
    """
    raw = "\n".join([doc.title, normalize(doc.content), ",".join(sorted(doc.tags))])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def chunk_document(
    doc: PostDocument,
    config: ChunkingConfig | None = None,
) -> list[Chunk]:
    """把文章切成父块 + 子块，顺序稳定、可重复。

    返回值里父块与子块混排但各自 `chunk_index` 连续；调用方按 `chunk_type` 分流。
    同一输入永远得到同一输出（不依赖字典序、随机数或当前时间）。
    """
    cfg = config or ChunkingConfig()
    cfg.validate()

    text = normalize(doc.content)
    if not text:
        return []

    version = content_version(doc)
    blocks = _split_sections(text)

    chunks: list[Chunk] = []
    parent_index = 0
    child_index = 0
    for heading_path, section_text, section_start in blocks:
        if not section_text.strip():
            continue
        parent = _make_chunk(
            doc, "parent", parent_index, None, heading_path, section_text, section_start, version
        )
        chunks.append(parent)

        for piece, offset in _split_children(section_text, section_start, cfg):
            chunks.append(
                _make_chunk(
                    doc, "child", child_index, parent_index, heading_path, piece, offset, version
                )
            )
            child_index += 1
        parent_index += 1

    return chunks


def _make_chunk(
    doc: PostDocument,
    chunk_type: str,
    index: int,
    parent_index: int | None,
    heading_path: str,
    text: str,
    char_start: int,
    version: str,
) -> Chunk:
    stripped = text.strip()
    lead = len(text) - len(text.lstrip())
    start = char_start + lead
    end = start + len(stripped)
    content_hash = hashlib.sha256(stripped.encode("utf-8")).hexdigest()
    return Chunk(
        # chunk_id 必须稳定且可复现：重索引时同一片段要得到同一 id（幂等的前提）
        chunk_id=f"p{doc.post_id}:v{version}:{chunk_type[:1]}{index}",
        post_id=doc.post_id,
        chunk_type=chunk_type,
        chunk_index=index,
        parent_index=parent_index,
        heading_path=heading_path,
        text=stripped,
        char_start=start,
        char_end=end,
        content_hash=content_hash,
        version=version,
    )


def _split_sections(text: str) -> list[tuple[str, str, int]]:
    """按 `##`/`###` 标题切分章节，返回 (标题路径, 章节正文, 起始偏移)。

    代码块里的 `#` 不算标题（否则 Python 注释会被当成章节）。
    """
    lines = text.split("\n")
    sections: list[tuple[str, str, int]] = []
    current_path: list[str] = []
    buffer: list[str] = []
    offset = 0
    buffer_start = 0
    in_fence = False

    def flush() -> None:
        nonlocal buffer, buffer_start
        body = "\n".join(buffer)
        if body.strip():
            sections.append((" / ".join(current_path), body, buffer_start))
        buffer = []

    for line in lines:
        line_start = offset
        offset += len(line) + 1  # +1 是换行符
        if _FENCE.match(line):
            in_fence = not in_fence
            buffer.append(line)
            continue
        match = None if in_fence else _HEADING.match(line)
        if match:
            flush()
            level = len(match.group(1))
            title = match.group(2).strip()
            # 维护标题路径：同级或更高级标题替换掉更深的层级
            del current_path[max(level - 1, 0) :]
            while len(current_path) < level - 1:
                current_path.append("")
            current_path.append(title)
            buffer_start = line_start + len(line) + 1
            continue
        if not buffer:
            buffer_start = line_start
        buffer.append(line)

    flush()
    if not sections:
        # 完全没有标题的文章：整篇作为一个章节，仍然能切出子块
        sections.append(("", text, 0))
    return sections


def _split_children(text: str, base_offset: int, cfg: ChunkingConfig) -> list[tuple[str, int]]:
    """把章节切成子块：优先在句子边界切，长句再按长度硬切。

    返回 (片段文本, 绝对起始偏移)。
    """
    pieces: list[tuple[str, int]] = []
    cursor = 0
    length = len(text)

    while cursor < length:
        hard_end = min(cursor + cfg.child_size, length)
        if hard_end < length:
            end = _best_boundary(text, cursor, hard_end, cfg.min_child_size)
        else:
            end = length
        piece = text[cursor:end]
        if piece.strip():
            pieces.append((piece, base_offset + cursor))
        if end >= length:
            break
        # 下一块从「上一块结尾往前 overlap 个字符」开始，保证跨块语义连续
        cursor = max(end - cfg.child_overlap, cursor + 1)

    return pieces


def _best_boundary(text: str, start: int, hard_end: int, min_size: int) -> int:
    """在 [start+min_size, hard_end] 里选一个切点。

    两条约束（都来自实测）：
    1. 切点不能太靠前（`>= min_size`），否则块会碎成一句话一块；
    2. 切点也不能太靠后（剩余长度 `>= min_size`），否则会留下一个极短的尾巴。

    中文技术文章一句话常只有 8~15 字，如果一味「见到句号就切」，
    300 字的目标块长会变成一堆 15 字碎块，召回质量反而下降 ——
    因此这里只在**同时满足两条约束**的候选里取最后一个；没有合适候选就用硬边界。
    """
    window = text[start:hard_end]
    remaining_after_hard_end = len(text) - hard_end
    candidates: list[int] = []
    for match in _SENTENCE_END.finditer(window):
        position = match.end()
        if position < min_size:
            continue
        # 尾巴约束：切点之后剩下的内容（本窗口剩余 + 窗口外）不能太小
        if (len(window) - position) + remaining_after_hard_end < min_size:
            continue
        candidates.append(position)
    if candidates:
        return start + candidates[-1]

    # 退而求其次：逗号/顿号等次一级边界，同样受两条约束
    for separator in ("，", "、", ",", " "):
        position = window.rfind(separator)
        if position >= min_size and (len(window) - position) + remaining_after_hard_end >= min_size:
            return start + position + 1
    return hard_end
