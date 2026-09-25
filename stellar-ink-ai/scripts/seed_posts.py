"""CLI：打印种子内容包里的每篇文章（id / 标题 / 正文摘要）。

解析逻辑在 `app/rag/seed_corpus.py`（评测接口也要用它，而 `scripts/` 不进安装包），
这里只保留命令行入口与人类可读的输出格式。

用法：``uv run python scripts/seed_posts.py``
"""

from __future__ import annotations

import sys

from app.rag.seed_corpus import (
    SEED_SQL_RELATIVE,
    SeedCorpusError,
    SeedPost,
    default_seed_sql,
    load_seed_posts,
    parse_seed_posts,
)

# 控制台编码助手：本文件会被**两种方式**加载 —— 直接运行（scripts/ 在 sys.path[0]）
# 与 pytest 的 `from scripts.x import y`。这里用包内相对导入，两种方式都成立。
# （写成 `from console import ...` 在 pytest 那条路径下会 ModuleNotFoundError，
#   这一点由 tests/test_scripts.py 盯着。）
from scripts.console import use_utf8_console

__all__ = [
    "SEED_SQL_RELATIVE",
    "SeedPost",
    "default_seed_sql",
    "load_seed_posts",
    "parse_seed_posts",
]


def main() -> None:
    try:
        posts = load_seed_posts()
    except SeedCorpusError as error:
        raise SystemExit(str(error)) from error
    print(f"共 {len(posts)} 篇\n")
    for post in posts:
        body = post.plain.replace("\n", " ")
        print(f"--- id={post.post_id} tags={'/'.join(post.tags)}")
        print(f"    标题：{post.title}")
        print(f"    正文：{body[:180]}")
    print(f"\n合计 {len(posts)} 篇", file=sys.stderr)


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()
