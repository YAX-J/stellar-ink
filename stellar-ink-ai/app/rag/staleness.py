"""增量失效（E4-11）：文章改了，**只**让受影响的知识条目失效。

问题：一次构建把主张写进库之后，文章会被继续编辑。旧主张不会自己消失 ——
于是读者侧会看到「引用的那段话已经不在正文里了」（E4-3 界面上那句
「正文里找不到这段文字 —— 文章可能在抽取之后改过」正是这个状态的兜底）。
整库重跑能修好，但要为**没变过的文章**再花一遍模型调用。

判定只需要两样东西：
* **当前语料**（Python 侧唯一知道切块结果的地方）：每个 `(postId, chunkIndex)` 的内容哈希；
* **库里存的主张锚点**：`(postId, chunkIndex, contentHash)`。

三种状态，处置**不一样**，所以必须分开报：
1. `current`：锚点还能在语料里找到同样的哈希 → 不用动；
2. `stale`：这个 `(postId, chunkIndex)` 还在，但**哈希变了**（文章改过）→ 整篇该重建；
3. `orphan`：这个段落**已经不存在**了（文章删了、或删短了）→ 那些主张的引文必然失效，
   得删掉 —— 留着就是永久性的错误引用。

⚠️ 判定与重建**分开**：这份报告是免费的，重建要花钱打模型。
把两者合成一个「自动重建」看起来更省事，代价是**没人知道钱花在哪**，
而且一次误判（比如语料缓存没刷新）会让它在后台反复烧钱。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True, slots=True)
class StaleReport:
    """一次失效盘点的结果（计数与**要动的文章**分开给）。"""

    #: 查过多少条主张
    checked: int = 0
    #: 锚点仍然对得上的条数
    current: int = 0
    #: 段落内容变了的条数（文章改过）
    stale: int = 0
    #: 段落已经不在语料里的条数（文章删了或删短了）
    orphan: int = 0
    #: 需要重建的文章（`stale` 涉及的文章）
    stale_post_ids: list[int] = field(default_factory=list)
    #: 有孤立段落的文章（需要清理主张，不一定需要重建）
    orphan_post_ids: list[int] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "checked": self.checked,
            "current": self.current,
            "stale": self.stale,
            "orphan": self.orphan,
            "stalePostIds": list(self.stale_post_ids),
            "orphanPostIds": list(self.orphan_post_ids),
            "notes": self.notes(),
        }

    def notes(self) -> list[str]:
        """给人看的解释：**状态的含义**必须写出来，否则「stale 3」这种数字没人知道该干什么。"""
        notes: list[str] = []
        if self.stale:
            notes.append(
                f"{self.stale} 条主张所在的段落内容变了（{len(self.stale_post_ids)} 篇文章）——"
                "需要重建这些文章。"
            )
        if self.orphan:
            notes.append(
                f"{self.orphan} 条主张引用的段落已经不存在（{len(self.orphan_post_ids)} 篇文章）——"
                "它们无法再回到原文，应当清理。"
            )
        if not self.stale and not self.orphan:
            notes.append(f"盘点了 {self.checked} 条主张，都还对得上原文，不需要重建。")
        return notes


def stale_claims(chunks: list[Any], stored: list[Any]) -> StaleReport:
    """盘点库里存的主张锚点相对当前语料是否还有效。

    :param chunks: 当前语料的 `IndexedChunk`（要有 `post_id` 与 `payload`）
    :param stored: 库里存的主张锚点，每项要有 `post_id` / `chunk_index` / `content_hash`
    :returns `StaleReport`

    ⚠️ 只比对**段落哈希**，不去比主张文本：文本是模型的输出，重跑一次本来就可能变；
    而「原文是否还是那一版」才是「这条主张还能不能核对」的判据。
    拿文本来判的话，每次构建都会被判成「全都变了」—— 那等于没有增量，只是看起来有。
    """
    current: dict[tuple[int, int], str] = {}
    for chunk in chunks:
        post_id = int(getattr(chunk, "post_id", 0))
        payload = getattr(chunk, "payload", None) or {}
        index = payload.get("chunkIndex")
        if not isinstance(index, (int, str)):
            continue
        try:
            chunk_index = int(index)
        except ValueError:
            continue
        current[(post_id, chunk_index)] = str(payload.get("contentHash") or "")

    checked = current_count = stale = orphan = 0
    stale_posts: set[int] = set()
    orphan_posts: set[int] = set()
    for claim in stored:
        post_id = int(getattr(claim, "post_id", 0))
        chunk_index = int(getattr(claim, "chunk_index", 0))
        content_hash = str(getattr(claim, "content_hash", "") or "")
        checked += 1
        known = current.get((post_id, chunk_index))
        if known is None:
            orphan += 1
            orphan_posts.add(post_id)
        elif known and content_hash and known != content_hash:
            stale += 1
            stale_posts.add(post_id)
        else:
            # 哈希缺失（老数据或列没填）时按「当前」处理：**不能**凭缺失判失效，
            # 那会把整库判成过期、逼着人做一次全量重建
            current_count += 1

    return StaleReport(
        checked=checked,
        current=current_count,
        stale=stale,
        orphan=orphan,
        stale_post_ids=sorted(stale_posts),
        orphan_post_ids=sorted(orphan_posts),
    )
