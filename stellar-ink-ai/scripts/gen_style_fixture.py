"""生成写作画像的跨语言契约 fixture（两侧单测读同一份）。

**刻意不依赖种子内容包**：种子语料会随文章增删而变，用它当契约向量的话，
每加一篇文章契约测试就红一次 —— 那种红没有信息量，只会让人把断言改松。
这里用一段固定的小样本，数字稳定、可人工复算。

用法（仓库根或 stellar-ink-ai 目录下）::

    uv run python scripts/gen_style_fixture.py
"""

from __future__ import annotations

import json
from dataclasses import asdict
from pathlib import Path

from app.rag.style import StyleSettings, build_style_profile

# 控制台编码助手与本文件同目录：uv run python scripts/x.py 时该目录就是 sys.path[0]
from console import use_utf8_console

FIXTURE = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "writing_style_result.json"
#: 请求侧 fixture 复用既有的 `authorId` 语义（Java 侧 DTO 与之一致）
REQUEST_FIXTURE = (
    Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "writing_style_request.json"
)

#: 固定样本：语气相近、句短、爱用「其实/所以」的短文，合计约 750 字。
#: 内容固定写死，**不要改成读种子语料**（见模块 docstring）；
#: 也别指望「够短就行」——画像有 500 字下限，样本太短会直接量不出画像。
#: （第一版每篇只有 120 字左右，合计 356 字，脚本直接拒绝生成。）
SAMPLES = [
    {
        "title": "写慢一点",
        "tags": ["随笔", "生活"],
        "plain": (
            "其实我写得慢。一天五百字，不多。\n"
            "早上写一点，晚上再写一点。其实不写也行，但不写会想。\n"
            "所以我不追热点。热点太快了，我跟不上——那就慢一点。\n"
            "慢不是懒。慢是因为想不清楚的时候，快只会把不清楚写下来。\n"
            "我试过赶。赶出来的东西，第二天自己都不想看。\n"
            "现在我留着草稿。过一晚再看，删掉一半，剩下的才是真的。\n"
            "所以别问我一天写多少。问我一天删多少，更接近实情。\n"
            "删掉的那些也不是白写。它们替我把路走了一遍，我才知道哪条不通。\n"
            "写字像走路。走得慢的人，往往记得路边的样子。\n"
            "我宁愿记得。记得比写完重要。\n"
        ),
    },
    {
        "title": "窗边的位置",
        "tags": ["随笔"],
        "plain": (
            "我习惯坐在窗边。灯开着，外面黑着。\n"
            "其实写不出来的时候更多。坐着，看一会儿，再写一句。\n"
            "所以慢不是姿态，是没有别的办法。\n"
            "窗边有个好处：看得见天在变。天亮到天黑，中间很长。\n"
            "我就不急了。一天能写一段，也算写过。\n"
            "写不动的时候，我数路上的人。数到二十，再回到字上。\n"
            "外面有骑车的人，有牵狗的人，有站着抽烟的人。\n"
            "他们不知道有人在看。这让我觉得安全——我也常常不知道自己在被谁看着。\n"
            "其实写下来，就是承认自己被看见。承认这件事，需要一点勇气。\n"
            "所以我先坐在窗边。坐稳了，再动笔。\n"
        ),
    },
    {
        "title": "关于诚实",
        "tags": ["随笔", "写作"],
        "plain": (
            "写作最难的是诚实。诚实比漂亮难。\n"
            "其实我知道哪里在回避，只是不想承认。\n"
            "所以我逼自己写下来。写完就不回避了。\n"
            "有一次我删掉一整段。那段写得很顺，但它是假的。\n"
            "删完心里空了一下，然后又踏实。空的这一段，才是真的。\n"
            "后来我定了规矩：写不下去的地方，标一个记号，第二天再回去看。\n"
            "记号多了，我就知道那篇有问题。不是文笔的问题，是我还没想清楚。\n"
            "想不清楚就写，写出来只会是漂亮话。漂亮话最省力，也最没用。\n"
            "所以我宁可写得难看一点。难看但真，比好看但空要好。\n"
            "这句我留着提醒自己：先诚实，再谈别的。\n"
        ),
    },
]


class _Post:
    """与 `app/rag/style.py` 的 PostLike 协议一致的最小样本类型。"""

    def __init__(self, title: str, plain: str) -> None:
        self._title = title
        self._plain = plain

    @property
    def title(self) -> str:
        return self._title

    @property
    def plain(self) -> str:
        return self._plain


def build() -> dict:
    posts = [_Post(sample["title"], sample["plain"]) for sample in SAMPLES]
    profile = build_style_profile(
        posts,
        tags_per_title=[sample["tags"] for sample in SAMPLES],
        settings=StyleSettings(),
    )
    if profile is None:
        raise SystemExit("固定样本太短，量不出画像：请加长 SAMPLES（契约向量必须稳定）")
    return {
        "authorId": 1,
        "evidenceSufficient": True,
        "profile": asdict(profile),
        "notes": "固定样本生成的契约向量（见 scripts/gen_style_fixture.py）",
    }


def camel(payload: object) -> object:
    """蛇形 → 驼峰：fixture 里的键名对所有语言都是同一份（见 app/schemas/base.py）。"""
    if isinstance(payload, dict):
        return {_to_camel(key): camel(value) for key, value in payload.items()}
    if isinstance(payload, list):
        return [camel(item) for item in payload]
    return payload


def _to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.capitalize() for part in rest)


def main() -> None:
    result = camel(build())
    FIXTURE.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    REQUEST_FIXTURE.write_text(
        json.dumps({"authorId": 1, "maxSamples": 20}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"已写入 {FIXTURE.relative_to(FIXTURE.parents[3])}")
    print(json.dumps(result["profile"], ensure_ascii=False))


if __name__ == "__main__":
    # 控制台编码：Windows 默认 GBK，脚本里的箭头/勾叉/破折号会让 print 抛异常
    use_utf8_console()
    main()
