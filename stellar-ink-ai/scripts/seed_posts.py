"""从种子内容包（`deploy/sql/02_init-data.sql`）里提取文章，供黄金集与调试使用。

用法：``uv run python scripts/seed_posts.py`` 会打印每篇文章的 id / 标题 / 正文摘要。
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

SEED_SQL = Path(__file__).resolve().parents[2] / "deploy" / "sql" / "02_init-data.sql"

_INSERT_POST = re.compile(r"INSERT IGNORE INTO `post`\s*\(", re.IGNORECASE)
_ANY_INSERT = re.compile(r"INSERT IGNORE INTO `", re.IGNORECASE)
# 一行一条记录：id、user_id、title、content、tags、字数、状态、glow、created、updated
_POST_ROW = re.compile(
    r"\((\d+),\s*(\d+),\s*'((?:[^']|'')*)',\s*'((?:[^']|'')*)',\s*'((?:[^']|'')*)',"
    r"\s*(\d+),\s*(\d+),\s*(\d+),\s*'([^']*)',\s*'([^']*)'\)",
    re.DOTALL,
)


@dataclass(frozen=True, slots=True)
class SeedPost:
    post_id: int
    title: str
    content: str
    tags: list[str]

    @property
    def plain(self) -> str:
        """把 SQL 里的转义还原成可读正文。"""
        return self.content.replace("\\n", "\n").replace("''", "'")


def load_seed_posts(sql_path: Path = SEED_SQL) -> list[SeedPost]:
    raw = sql_path.read_text(encoding="utf-8")
    start = _INSERT_POST.search(raw)
    if start is None:
        raise SystemExit(f"在 {sql_path} 里找不到 post 的 INSERT")
    rest = raw[start.end() :]
    end = _ANY_INSERT.search(rest)
    block = rest[: end.start()] if end else rest

    posts: list[SeedPost] = []
    for match in _POST_ROW.finditer(block):
        posts.append(
            SeedPost(
                post_id=int(match.group(1)),
                title=match.group(3).replace("''", "'"),
                content=match.group(4),
                tags=[tag.strip() for tag in match.group(5).split(",") if tag.strip()],
            )
        )
    return posts


def main() -> None:
    posts = load_seed_posts()
    print(f"共 {len(posts)} 篇\n")
    for post in posts:
        body = post.plain.replace("\n", " ")
        print(f"--- id={post.post_id} tags={'/'.join(post.tags)}")
        print(f"    标题：{post.title}")
        print(f"    正文：{body[:180]}")
    print(f"\n合计 {len(posts)} 篇", file=sys.stderr)


if __name__ == "__main__":
    main()
