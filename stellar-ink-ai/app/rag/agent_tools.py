"""Agent 的只读工具集：把已有的检索与画像能力包成模型能调用的形状。

**全部只读**（`ToolSpec.read_only=True`，`ToolBox` 会在装配时把关）。这一层的价值不是
「多几个函数」，而是把「站内能查什么」收成一份**可打印、可审计**的清单：
它既进提示词（`ToolBox.describe()`），也是审计日志里「这次跑了哪些工具」的依据。

刻意不做的两件事：
- **不做写操作**（发文章、改标签、删评论）：红线 §7.4 —— 第一版 Agent 全只读。
  真要做时也应该走「提议 → 人确认」而不是让 Agent 直接落库。
- **不查站外**：网络搜索会引入无法引用、无法审计的内容，与「答案必须能定位回原文」冲突。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.rag.agent import ToolResult, ToolSpec
from app.rag.pipeline import IndexedChunk, RetrievalPipeline
from app.rag.qa import QaSettings
from app.rag.style import StyleSettings, build_style_profile
from app.schemas.common import Citation, Role

#: 一次检索默认取多少篇：Agent 会自己决定要不要再查，所以首轮不必贪多
DEFAULT_TOOL_TOP_K = 4
#: 工具结果里每条片段保留多少字
DEFAULT_SNIPPET_CHARS = 180


@dataclass(slots=True)
class SearchPostsTool:
    """按问题检索站内已发布文章。返回**可直接进提示词**的摘录 + 结构化引用。"""

    pipeline: RetrievalPipeline
    settings: QaSettings = field(default_factory=QaSettings)
    snippet_chars: int = DEFAULT_SNIPPET_CHARS
    _by_id: dict[str, IndexedChunk] = field(default_factory=dict, init=False)

    def __post_init__(self) -> None:
        self._by_id = {chunk.chunk_id: chunk for chunk in self.pipeline.corpus}

    @property
    def name(self) -> str:
        return "search_posts"

    @property
    def description(self) -> str:
        return "按问题检索站内已发布文章，返回相关段落摘录与可引用的 postId"

    async def run(self, arguments: dict[str, Any]) -> ToolResult:
        question = str(arguments.get("question") or "").strip()
        if not question:
            return ToolResult(summary="检索需要 question 参数。", label="参数缺失")
        raw_top_k = arguments.get("topK")
        top_k = (
            raw_top_k if isinstance(raw_top_k, int) and 1 <= raw_top_k <= 20 else DEFAULT_TOOL_TOP_K
        )

        outcome = await self.pipeline.retrieve(question, top_k=top_k)
        if outcome.refused or not outcome.hits:
            # 「没有依据」是**结果**不是错误：Agent 应当据此收尾，而不是换个说法再查一遍
            return ToolResult(summary="没有检索到相关段落。", label="无命中")

        lines: list[str] = []
        citations: list[Citation] = []
        for index, hit in enumerate(outcome.hits, start=1):
            chunk = self._by_id.get(hit.chunk_id)
            if chunk is None:
                # 命中回不到语料说明索引与语料不是同一批：宁可报错也不要拼出假引用
                return ToolResult(
                    summary=f"检索命中不在语料里：{hit.chunk_id}（需要重建索引）",
                    label="索引不一致",
                )
            snippet = _snippet(chunk, self.snippet_chars)
            title = chunk.title or f"文章 {chunk.post_id}"
            lines.append(f"[{index}]《{title}》(postId={chunk.post_id})：{snippet}")
            citations.append(
                Citation(
                    post_id=chunk.post_id,
                    title=title,
                    chunk_index=_chunk_index(chunk),
                    snippet=snippet,
                    score=float(hit.score),
                )
            )
        return ToolResult(
            summary="\n".join(lines),
            citations=citations,
            label=f"检索到 {len(citations)} 段",
        )


@dataclass(slots=True)
class AuthorStyleTool:
    """量当前作者的写作习惯。**只给统计量，不含原句**（见 `app/rag/style.py`）。"""

    posts: list[Any]
    tags_per_title: list[list[str]] = field(default_factory=list)
    settings: StyleSettings = field(default_factory=StyleSettings)

    @property
    def name(self) -> str:
        return "author_style"

    @property
    def description(self) -> str:
        return "量当前作者的写作习惯（句长、标点、关联词），用于让建议贴合他本来的语气"

    async def run(self, arguments: dict[str, Any]) -> ToolResult:
        del arguments  # 只读且无参数：作者身份由调用方在装配时确定，不接受从提示词传入
        profile = build_style_profile(
            self.posts, tags_per_title=self.tags_per_title, settings=self.settings
        )
        if profile is None:
            return ToolResult(summary="该作者已发布文章不足，暂时量不出画像。", label="样本不足")
        # describe() 本身就是给模型看的中文，直接复用：两处各写一份迟早会不一致
        return ToolResult(summary=profile.describe(), label="画像已量出")


def read_only_tools(
    *,
    pipeline: RetrievalPipeline | None = None,
    style_posts: list[Any] | None = None,
    tags_per_title: list[list[str]] | None = None,
) -> list[ToolSpec]:
    """装配第一版只读工具集。

    哪些工具可用取决于调用方给什么：没有检索管线就只给画像，反之亦然。
    一个工具都没有时 `ToolBox` 会直接报错 —— 那种 Agent 只会瞎猜。

    E3-3 起每条工具还带上 MCP 需要的三样：`input_schema`（客户端据此构造调用）、
    `required_role`（谁能调）与 `timeout_ms`。**schema 里没有的参数一律会被 MCP 层拒掉** ——
    这是「客户端不能靠自行构造参数扩大权限」的落点：作者身份来自签名的 `X-AI-User-Id`，
    而不是请求体里的某个字段（所以 `author_style` 的 schema 是空的）。
    """
    tools: list[ToolSpec] = []
    if pipeline is not None:
        tool = SearchPostsTool(pipeline=pipeline)
        tools.append(
            ToolSpec(
                name=tool.name,
                description=tool.description,
                handler=tool.run,
                arguments="question（必填）、topK（1-20，可选）",
                input_schema={
                    "type": "object",
                    "properties": {
                        "question": {
                            "type": "string",
                            "description": "要检索的问题",
                            "minLength": 1,
                            "maxLength": 500,
                        },
                        "topK": {
                            "type": "integer",
                            "description": f"返回几段，1-20，默认 {DEFAULT_TOOL_TOP_K}",
                            "minimum": 1,
                            "maximum": 20,
                        },
                    },
                    "required": ["question"],
                    "additionalProperties": False,
                },
                required_role=Role.READER,
                timeout_ms=20_000,
            )
        )
    if style_posts:
        style_tool = AuthorStyleTool(
            posts=list(style_posts), tags_per_title=list(tags_per_title or [])
        )
        tools.append(
            ToolSpec(
                name=style_tool.name,
                description=style_tool.description,
                handler=style_tool.run,
                arguments="无参数",
                # 空 schema = 不接受任何参数。作者身份由签名头决定，**不能**从参数传
                input_schema={"type": "object", "properties": {}, "additionalProperties": False},
                required_role=Role.AUTHOR,
                timeout_ms=10_000,
            )
        )
    return tools


def _chunk_index(chunk: IndexedChunk) -> int:
    raw = chunk.payload.get("chunkIndex")
    return raw if isinstance(raw, int) and raw >= 0 else 0


def _snippet(chunk: IndexedChunk, limit: int) -> str:
    """取原文片段：优先 payload 里的正文，回退到「去掉标题行」。"""
    raw = str(chunk.payload.get("text") or "")
    if not raw:
        raw = chunk.text.split("\n", 1)[1] if "\n" in chunk.text else chunk.text
    body = raw.strip()
    return body if len(body) <= limit else body[:limit].rstrip() + "…"
