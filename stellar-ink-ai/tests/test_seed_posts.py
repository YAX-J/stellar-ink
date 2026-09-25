"""种子内容包解析器的回归测试。

为什么值得单独测：检索评测的语料就来自 `deploy/sql/02_init-data.sql`，
而这个文件里 `post` 有**三个** INSERT 块（列数还不一样）、时间戳既有字符串也有 `NOW()`。
早先的解析器只读第一块、只认带引号的时间戳，于是 13–15 号短文被静默丢掉，
评测语料悄悄少三篇却没有任何报错 —— 召回率因此长期偏低而没人怀疑。
这里用「独立数一遍行数」的方式把「少解析」变成会失败的断言。
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.rag.seed_corpus import (
    SEED_SQL_RELATIVE,
    SeedCorpusError,
    default_seed_sql,
    load_seed_posts,
)

#: 每行记录都以 `(id, user_id,` 开头且顶格书写 —— 与解析器实现无关的独立数法
_ROW_HEAD = re.compile(r"^\((\d+),\s*\d+,", re.MULTILINE)
_INSERT = re.compile(r"INSERT\s+IGNORE\s+INTO\s+`(?P<table>\w+)`", re.IGNORECASE)

SEED_SQL = default_seed_sql()


def _sql_post_ids() -> list[int]:
    """直接从 SQL 文本里数出所有 post 记录的 id，不经过解析器。"""
    raw = SEED_SQL.read_text(encoding="utf-8")
    matches = list(_INSERT.finditer(raw))
    ids: list[int] = []
    for index, match in enumerate(matches):
        if match.group("table").lower() != "post":
            continue
        end = matches[index + 1].start() if index + 1 < len(matches) else len(raw)
        values = re.split(r"\bVALUES\b", raw[match.end() : end], maxsplit=1, flags=re.IGNORECASE)
        if len(values) == 2:
            ids.extend(int(found) for found in _ROW_HEAD.findall(values[1]))
    return ids


def test_sql_actually_has_three_post_blocks() -> None:
    """前提断言：SQL 结构变了就该有人回来看这个测试。"""
    assert SEED_SQL.exists(), f"找不到种子内容包：{SEED_SQL}"
    assert len(_sql_post_ids()) > 15, "post 块数量或书写格式变了，解析器可能又要漏文章"


def test_parses_every_post_row() -> None:
    """核心断言：解析结果必须与独立数出来的行数、id 完全一致。"""
    parsed = [post.post_id for post in load_seed_posts()]

    assert parsed == _sql_post_ids()


def test_expression_timestamps_are_not_dropped() -> None:
    """13–15 号短文用的是 `NOW()` 时间戳，曾整批被正则丢掉。"""
    posts = {post.post_id for post in load_seed_posts()}

    assert {13, 14, 15} <= posts, "时间戳写成 NOW() 的行被解析器漏掉了"


def test_blocks_without_view_count_still_parse() -> None:
    """早期块没有 `view_count`、后期的块有；两种列数都要能吃下。"""
    by_id = {post.post_id: post for post in load_seed_posts()}

    assert by_id[1].title and by_id[1].plain  # 无 view_count 的块
    assert by_id[16].title and by_id[16].plain  # 有 view_count 的块


def test_text_fields_are_unpacked_cleanly() -> None:
    for post in load_seed_posts():
        assert post.title.strip(), f"{post.post_id} 标题为空"
        assert post.plain.strip(), f"{post.post_id} 正文为空"
        assert "\\n" not in post.plain, f"{post.post_id} 正文里还留着字面量 \\n"
        assert "''" not in post.plain, f"{post.post_id} 正文里还留着转义单引号"
        assert all(tag.strip() for tag in post.tags), f"{post.post_id} 出现了空标签"


def test_default_path_points_at_the_repo_seed_file() -> None:
    assert Path(SEED_SQL).name == "02_init-data.sql"
    assert Path(SEED_SQL).parent.name == "sql"
    assert Path(SEED_SQL) == SEED_SQL.parent.parent.parent / SEED_SQL_RELATIVE


def test_cli_re_exports_the_app_loader() -> None:
    """CLI 只是一层壳：它必须用 app 里那份实现，否则两边会慢慢分叉。"""
    from scripts import seed_posts

    assert seed_posts.load_seed_posts is load_seed_posts
    assert seed_posts.SeedPost is load_seed_posts.__globals__["SeedPost"]


def test_missing_file_raises_a_library_error(tmp_path: Path) -> None:
    """库代码不该 `SystemExit`：一个请求打到解析失败不能把服务带走。"""
    with pytest.raises(SeedCorpusError, match="不存在"):
        load_seed_posts(tmp_path / "nope.sql")


def test_garbage_sql_raises_a_library_error(tmp_path: Path) -> None:
    broken = tmp_path / "broken.sql"
    broken.write_text("-- 只有注释，没有 INSERT\n", encoding="utf-8")

    with pytest.raises(SeedCorpusError, match="找不到 post 的 INSERT"):
        load_seed_posts(broken)
