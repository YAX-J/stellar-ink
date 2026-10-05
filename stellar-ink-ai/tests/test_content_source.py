"""线上语料来源（`ai_content_snapshot`）的读取契约。

这一层现在**尚未接线**（`corpus.py` 仍用种子包），所以用例盯的是「接线时最容易出错的三件事」：

1. **不做 SQL 反转义**：库里的正文是明文 Markdown。复用 `SeedPost` 会把代码块里的 `\\n`
   与成对的单引号改写掉 —— 检索出的原文与读者看到的原文不一致（这类错非常难发现）；
2. **失败要能被识别成「来源不可用」**（迁移没跑 / 连不上库），调用方据此回退种子包，
   而不是让「表不存在」冒成 500；
3. **过长的一篇整篇跳过并留痕**，而不是截断 —— 截断会让引用指向被切掉一半的文字。
"""

from __future__ import annotations

import pytest

from app.rag import content_source
from app.rag.content_source import CorpusSourceUnavailable, SnapshotDoc


def test_plain_keeps_markdown_verbatim() -> None:
    """正文里的 `\\n` 与单引号必须原样保留（不能像种子包那样反转义）。"""
    raw = "代码块：\n```python\nprint('a')\n```\n字面量：\\\\n 和 两个单引号 ''"
    doc = SnapshotDoc(post_id=1, title="标题", content=raw, tags=["写作"])

    assert doc.plain == raw
    assert "\\\\n" in doc.plain
    assert "''" in doc.plain


def test_doc_shape_matches_seed_post() -> None:
    """字段与 `SeedPost` 对齐：接线时下游（画像/检索/评测）一行都不用改。"""
    doc = SnapshotDoc(post_id=7, title="标题", content="正文", tags=["写作"], author_id=3)

    assert (doc.post_id, doc.title, doc.plain, doc.tags, doc.author_id) == (
        7,
        "标题",
        "正文",
        ["写作"],
        3,
    )


def test_missing_database_config_reports_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`MYSQL_*` 没给齐 → 明确的「来源不可用」，调用方据此回退种子包。"""
    for key in ("MYSQL_HOST", "MYSQL_DB", "MYSQL_USER"):
        monkeypatch.delenv(key, raising=False)

    with pytest.raises(CorpusSourceUnavailable) as error:
        content_source.load_snapshot_docs()

    assert "MYSQL_HOST" in str(error.value)


def test_missing_table_message_names_the_migration(monkeypatch: pytest.MonkeyPatch) -> None:
    """表不存在时，报错里必须直接给出「跑哪个脚本 + 怎么触发同步」。

    否则运维看到的是 `Table 'stellar_ink.ai_content_snapshot' doesn't exist`，
    要么以为是代码坏了，要么以为是权限问题 —— 而它只是迁移没跑。
    """
    monkeypatch.setenv("MYSQL_HOST", "127.0.0.1")
    monkeypatch.setenv("MYSQL_DB", "stellar_ink")
    monkeypatch.setenv("MYSQL_USER", "root")

    class _Cursor:
        def __enter__(self) -> _Cursor:
            return self

        def __exit__(self, *_: object) -> None:
            return None

        def execute(self, *_: object) -> None:
            raise RuntimeError("(1146, \"Table 'stellar_ink.ai_content_snapshot' doesn't exist\")")

    class _Connection:
        def cursor(self) -> _Cursor:
            return _Cursor()

        def close(self) -> None:
            return None

    class _Pymysql:
        class cursors:  # noqa: N801 - 模仿 pymysql 的模块结构
            DictCursor = object

        @staticmethod
        def connect(**_: object) -> _Connection:
            return _Connection()

    monkeypatch.setitem(__import__("sys").modules, "pymysql", _Pymysql)

    with pytest.raises(CorpusSourceUnavailable) as error:
        content_source.load_snapshot_docs()

    message = str(error.value)
    assert "19_ai_content_snapshot.sql" in message
    assert "corpus/sync" in message
