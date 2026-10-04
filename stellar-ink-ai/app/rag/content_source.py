"""语料来源：**线上公开内容**（`ai_content_snapshot` 投影表）。

为什么是这张表，而不是直接读 `post` / `note`：Python 不读写业务表（AGENTS §5 红线），
而「什么算可见内容」的规则只能有一份 —— 它在 content-service。链路是：

    content-service（唯一规则：已发布文章 + 已发布且 PUBLIC 的笔记）
        → ai-service 定时投影 → ai_content_snapshot（AI 域自己的表，只读）
        → 这里

所以本模块**只读** `ai_*` 表；表里出现什么，语料就是什么（私有笔记与草稿在上游就被排除了，
投影的删除侧还会把「转为私有/下架」的行清掉）。

⚠️ **为什么暂时只取 `kind='post'`**：文章与笔记的 id 各自自增（文章 3 与笔记 3 是两篇），
而整条引用链路（本服务的 `postId`、向量库 payload、Java 的引用 DTO、前端 `/posts/{id}` 跳转）
目前只认一个 `postId`。把 `kind + id` 打通是跨语言契约改动，要单独一刀做
（Python 模型 + 契约 fixture + Java DTO + docs/api 同时改，按仓库规矩）。
在那之前，公开笔记留在投影表里、**只是还没被索引** —— 这不会泄漏任何私有内容，
因为私有笔记根本没进这张表。

⚠️ **本模块目前尚未接线**（`corpus.py` 仍只用种子包）。原因是接线前必须先让投影带上
两个字段，否则写作画像会在运行时炸掉：

* `tags`：`app/api/v1/style.py` 用 `post.tags`（`tags_per_title`）；
* `author_id`：写作画像是**按作者取样**的（`post.author_id == author_id`），
  而投影表里没有作者列。

也就是说，⑧ 这一步的完整前置是：`/internal/corpus` 的**清单**补 `tags` 与 `authorId`
（现在只有单篇正文接口带 `tags`，两个都不带作者）→ `ai_content_snapshot` 加两列 →
同步写入 → 才轮到这里的切换。在那之前保持种子包，不动生产行为。

⚠️ **种子包回退只用于「离线/单测/迁移还没跑」**：表不存在或库连不上时回退
`deploy/sql/02_init-data.sql`（今天的现状），并且 `corpus_source()` 会**如实说明**是哪一种。
表存在但没有行时**不回退** —— 那可能是站内真的没有公开内容，
回退会把早先删掉的文章重新拿来回答（宁可没依据，也不复活已删内容）。
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass

# ⚠️ 必须 import `app.core.config`：`.env` 的加载挂在它的 import 期，而这里读的是 os.environ。
# 少了这行会「.env 里 mysql 配得齐齐的，这里却判定未配置」→ 静默回退种子包，
# 把「线上语料没接上」伪装成「还在用种子包」。与 config_source 同一个理由。
from app.core.config import ENV_FILE  # noqa: F401 - 只为触发 .env 加载（见上）

logger = logging.getLogger(__name__)

#: 单篇正文的读取上限（防止一行异常巨大的内容把内存吃光；正常文章远小于此）
MAX_DOC_CHARS = 200_000

#: 只读查询：**只碰 ai_* 表**，且只要文章（笔记见模块开头的说明）。
#: `content IS NOT NULL` 是必要的：没有正文的行切不出任何块，读进来只会变成噪声。
SELECT_PUBLISHED_POSTS = (
    "SELECT `content_id`, `title`, `content`, `tags`, `author_id` FROM `ai_content_snapshot` "
    "WHERE `kind` = 'post' AND `content` IS NOT NULL AND `content` <> '' "
    "ORDER BY `content_id`"
)


class CorpusSourceUnavailable(RuntimeError):
    """线上语料来源不可用（迁移没跑、连不上库、依赖缺失）—— 调用方据此回退种子包。"""


@dataclass(frozen=True, slots=True)
class SnapshotDoc:
    """投影表里的一篇文档。

    **刻意不复用 `SeedPost`**：那个类型的 `plain` 会做 SQL 反转义（`\\n` → 换行、`''` → `'`），
    因为种子里存的是被 SQL 转义过的文本；而库里的 `content` 本来就是明文 Markdown。
    复用会让「代码块里写着 `\\n`」或「一对单引号」被悄悄改写：
    检索出来的原文与读者看到的原文不一致。

    字段与 `SeedPost` **对齐**（`post_id` / `title` / `plain` / `tags` / `author_id`），
    这样将来切换来源时，下游（写作画像、检索管道、评测）一行都不用改。
    """

    post_id: int
    title: str
    content: str
    #: 标签。**投影表目前没有这一列**，所以这里先是空表；补列后由同步写入（见模块开头的说明）
    tags: list[str]
    #: 作者 id。**投影表目前没有这一列**（写作画像按作者取样需要它）
    author_id: int = 0

    @property
    def plain(self) -> str:
        """正文原文（不做任何反转义/裁剪）。"""
        return self.content


def mysql_configured() -> bool:
    """`MYSQL_*` 是否给齐（与 `config_source` 同一套变量、同一个判据）。"""
    return all(os.environ.get(key) for key in ("MYSQL_HOST", "MYSQL_DB", "MYSQL_USER"))


def load_snapshot_posts() -> list[SnapshotDoc]:
    """读投影表里的已发布文章。

    :raises CorpusSourceUnavailable: 表不存在 / 连不上库 / 没装 PyMySQL
        —— 都表示「这次读不到线上语料」（调用方据此回退种子包）
    """
    if not mysql_configured():
        raise CorpusSourceUnavailable(
            "MYSQL_HOST/MYSQL_DB/MYSQL_USER 未给齐，读不到 ai_content_snapshot"
        )
    try:
        import pymysql  # type: ignore[import-untyped]  # noqa: PLC0415 - 可选依赖，按需导入
    except ImportError as error:  # pragma: no cover - 取决于部署是否装了它
        raise CorpusSourceUnavailable("直连库读取语料需要 PyMySQL") from error

    try:
        connection = pymysql.connect(
            host=os.environ["MYSQL_HOST"],
            port=int(os.environ.get("MYSQL_PORT") or 3306),
            user=os.environ["MYSQL_USER"],
            password=os.environ.get("MYSQL_PASSWORD") or "",
            database=os.environ["MYSQL_DB"],
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            # 只读 + 显式超时：语料读不到应当快速回退，而不是把首屏请求挂住
            connect_timeout=3,
            read_timeout=10,
        )
    except Exception as error:  # noqa: BLE001 - 统一翻成「来源不可用」，不逐个列举驱动异常
        raise CorpusSourceUnavailable(
            f"连接 MySQL 失败（{os.environ.get('MYSQL_HOST')}:"
            f"{os.environ.get('MYSQL_PORT') or 3306}/{os.environ.get('MYSQL_DB')}）："
            f"{type(error).__name__}: {error}"
        ) from error

    try:
        with connection.cursor() as cursor:
            cursor.execute(SELECT_PUBLISHED_POSTS)
            rows = list(cursor.fetchall())
    except Exception as error:  # noqa: BLE001 - 同上
        text = str(error)
        if "1146" in text or "doesn't exist" in text:
            raise CorpusSourceUnavailable(
                "ai_content_snapshot 表还不存在：先跑 deploy/sql/19_ai_content_snapshot.sql"
                "（已跑过旧版 19 的再跑 20_ai_content_snapshot_content.sql），"
                "然后让 ai-service 同步一次（POST /ai/admin/corpus/sync）"
            ) from error
        raise CorpusSourceUnavailable(
            f"查询 ai_content_snapshot 失败：{type(error).__name__}: {text}"
        ) from error
    finally:
        connection.close()

    docs: list[SnapshotDoc] = []
    for row in rows:
        content = str(row.get("content") or "")
        if not content:
            continue
        if len(content) > MAX_DOC_CHARS:
            # 截断会让「引用指向一段被切掉一半的文字」，所以宁可整篇跳过并留痕
            logger.warning(
                "跳过 ai_content_snapshot 中过长的一篇（content_id=%s，%s 字符 > %s）",
                row.get("content_id"),
                len(content),
                MAX_DOC_CHARS,
            )
            continue
        docs.append(
            SnapshotDoc(
                post_id=int(row.get("content_id") or 0),
                title=str(row.get("title") or ""),
                content=content,
                # tags / author_id 投影表暂时没有：等补列后在这里取值（`row.get("tags")` 需要
                # 按逗号拆分，`author_id` 直接转 int）。现在给空值而不是伪造，是为了让
                # 「界面上标签为空、画像取不到样本」表现成显而易见的缺数据，而不是错数据。
                tags=[part.strip() for part in str(row.get("tags") or "").split(",") if part.strip()],  # noqa: E501 - 拆开反而不易读
                author_id=int(row.get("author_id") or 0),
            )
        )
    return docs


def describe_source() -> str:
    """语料来源的人话描述（写进评测/问答响应，便于回答「这些数字是在哪份数据上算的」）。"""
    return f"ai_content_snapshot（MySQL {os.environ.get('MYSQL_HOST')}）"


def env_file_hint() -> str:
    """诊断用：当前 `.env` 是否存在（不参与任何判断）。"""
    return f".env：{'已加载' if ENV_FILE.is_file() else '不存在'}"
