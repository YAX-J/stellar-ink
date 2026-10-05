"""种子内容包解析：从 `deploy/sql/02_init-data.sql` 里取出文章。

为什么这个解析器住在 `app/` 而不是 `scripts/`：**评测接口要用它**。
`scripts/` 不进安装包（`pyproject.toml` 只打包 `app`），如果 app 里的代码去 import scripts，
部署到生产就会 ImportError —— 而本地因为脚本就在旁边，永远不会暴露。CLI 仍然保留在
`scripts/seed_posts.py`，它只是这里的一层薄封装。

为什么不用一条正则搞定：SQL 里 `post` 有**三个** INSERT 块，列数还不一样
（早期块没有 `view_count`），时间戳既有 `'2026-08-30 23:47:00'` 也有 `NOW() - INTERVAL 1 DAY`。
早先的写法只读第一块、只认带引号的时间戳，于是 13–15 号短文被**静默丢掉**，
评测语料悄悄少了三篇 —— 这类「少了几条也不报错」的解析器必须按行扫、并在这里说清列口径。

失败一律抛异常而不是 `SystemExit`：这是库代码，一个请求打到解析失败不该把服务带走。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

#: 相对仓库根的文件位置（用于向上查找，见 `default_seed_sql`）
SEED_SQL_RELATIVE = Path("deploy") / "sql" / "02_init-data.sql"

_INSERT = re.compile(r"INSERT\s+IGNORE\s+INTO\s+`(?P<table>\w+)`\s*\(", re.IGNORECASE)
#: 三个 post 块共用的列序（块之间差异只在有没有 view_count，这里只取需要的列）
_ID, _USER, _TITLE, _CONTENT, _TAGS = 0, 1, 2, 3, 4
_MIN_FIELDS = 10


class SeedCorpusError(RuntimeError):
    """种子内容包不可用（文件缺失或列口径变了）。"""


@dataclass(frozen=True, slots=True)
class SeedPost:
    post_id: int
    title: str
    content: str
    tags: list[str]
    #: 作者 id（`post.user_id`）。E1 的写作画像按作者取样，因此必须解析出来 ——
    #: 少了它，「这个作者平时怎么说话」就无从谈起。默认 0 表示列缺失（老种子包）。
    author_id: int = 0

    #: 内容种类。种子包**只解析文章**（`post` 块），所以这里恒为 `post`；
    #: 显式给出这个字段是为了满足 `PostLike` 协议 —— 语料里的文档标识是 `kind + id`
    #: （笔记走线上投影表，不走种子包）。
    kind: str = "post"

    @property
    def plain(self) -> str:
        """把 SQL 里的转义还原成可读正文。"""
        return self.content.replace("\\n", "\n").replace("''", "'")


def default_seed_sql() -> Path:
    """向上查找仓库里的种子内容包；找不到时给出可操作的错误。"""
    for parent in Path(__file__).resolve().parents:
        candidate = parent / SEED_SQL_RELATIVE
        if candidate.is_file():
            return candidate
    raise SeedCorpusError(
        f"找不到种子内容包 {SEED_SQL_RELATIVE}：它属于仓库文件，部署环境里需要另配语料来源"
    )


def _split_values(body: str) -> list[str]:
    """把 VALUES 之后的内容切成「一行一个字符串」，引号内的逗号与括号不算分隔。"""
    rows: list[str] = []
    depth = 0
    in_quote = False
    start = 0
    i = 0
    while i < len(body):
        char = body[i]
        if in_quote:
            if char == "'":
                if i + 1 < len(body) and body[i + 1] == "'":
                    i += 2
                    continue
                in_quote = False
        elif char == "'":
            in_quote = True
        elif char == "(":
            if depth == 0:
                start = i + 1
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                rows.append(body[start:i])
        i += 1
    return rows


def _split_fields(row: str) -> list[str]:
    """按顶层逗号切分一行的各列，`NOW()` 里的括号与引号内的逗号不会切断。"""
    fields: list[str] = []
    buffer: list[str] = []
    depth = 0
    in_quote = False
    i = 0
    while i < len(row):
        char = row[i]
        if in_quote:
            buffer.append(char)
            if char == "'":
                if i + 1 < len(row) and row[i + 1] == "'":
                    buffer.append("'")
                    i += 2
                    continue
                in_quote = False
        elif char == "'":
            in_quote = True
            buffer.append(char)
        elif char == "(":
            depth += 1
            buffer.append(char)
        elif char == ")":
            depth -= 1
            buffer.append(char)
        elif char == "," and depth == 0:
            fields.append("".join(buffer).strip())
            buffer = []
        else:
            buffer.append(char)
        i += 1
    fields.append("".join(buffer).strip())
    return fields


def _unquote(field: str) -> str | None:
    """只接受标准字符串字面量；返回**未还原转义**的内容（`plain` 负责还原）。"""
    if len(field) >= 2 and field.startswith("'") and field.endswith("'"):
        return field[1:-1]
    return None


def _post_blocks(raw: str) -> list[str]:
    """取出所有 `post` 表的 INSERT 语句体（到下一个 INSERT 为止）。"""
    matches = list(_INSERT.finditer(raw))
    blocks: list[str] = []
    for index, match in enumerate(matches):
        if match.group("table").lower() != "post":
            continue
        end = matches[index + 1].start() if index + 1 < len(matches) else len(raw)
        statement = raw[match.end() : end]
        # 丢掉列名清单，只留 VALUES 之后的元组，避免把列清单的右括号当成一行结束
        parts = re.split(r"\bVALUES\b", statement, maxsplit=1, flags=re.IGNORECASE)
        if len(parts) == 2:
            blocks.append(parts[1])
    return blocks


def parse_seed_posts(raw: str) -> list[SeedPost]:
    """从 SQL 文本里解析文章（纯函数，测试与接口共用同一实现）。"""
    blocks = _post_blocks(raw)
    if not blocks:
        raise SeedCorpusError("内容包里找不到 post 的 INSERT：列口径可能变了")

    posts: list[SeedPost] = []
    for block in blocks:
        for row in _split_values(block):
            fields = _split_fields(row)
            if len(fields) < _MIN_FIELDS:
                continue
            title = _unquote(fields[_TITLE])
            content = _unquote(fields[_CONTENT])
            tags = _unquote(fields[_TAGS])
            if title is None or content is None or tags is None:
                continue
            # user_id 是裸数字列；解析不出就给 0（老种子包可能没有这一列），
            # 而不是让整条记录消失 —— 文章本身对检索仍然有用
            author_id = int(fields[_USER]) if fields[_USER].isdigit() else 0
            posts.append(
                SeedPost(
                    post_id=int(fields[_ID]),
                    title=title.replace("''", "'"),
                    content=content,
                    tags=[tag.strip() for tag in tags.split(",") if tag.strip()],
                    author_id=author_id,
                )
            )
    if not posts:
        raise SeedCorpusError("内容包里解析出的文章数为 0：列口径可能变了")
    return posts


def load_seed_posts(sql_path: Path | None = None) -> list[SeedPost]:
    """读取并解析种子文章；`sql_path` 省略时向上查找仓库里的那份。"""
    path = sql_path if sql_path is not None else default_seed_sql()
    if not path.is_file():
        raise SeedCorpusError(f"种子内容包不存在：{path}")
    return parse_seed_posts(path.read_text(encoding="utf-8"))
