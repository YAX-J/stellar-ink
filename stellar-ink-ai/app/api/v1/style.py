"""写作风格画像接口（仅内网可达，由 Java `ai-service` 转发）。

这是 E 阶段的第一刀（E1 写作记忆）：**只量、不写**。
画像全部由 `app/rag/style.py` 从作者已发表文章的正文现算，
不落库、不进索引、不参与检索 —— 因此没有任何需要清理的派生状态。

当前语料来源与问答/Copilot 一致（种子内容包，按 `post.user_id` 过滤作者）。
真实形态是 Java 把该作者已发布的文章推过来（或索引里按 `userId` 过滤），
届时只需替换 `_samples_for` 这一处。
"""

import logging
from dataclasses import asdict
from functools import lru_cache

from fastapi import APIRouter

from app.rag.seed_corpus import SeedPost, load_seed_posts
from app.rag.style import StyleSettings, build_style_profile
from app.schemas.style import WritingStyleProfile, WritingStyleRequest, WritingStyleResult

logger = logging.getLogger(__name__)

router = APIRouter(tags=["writing"])

#: 契约口径说明：写进响应 `notes`，让前端能如实告诉作者「这些数字是怎么来的」
NOTES = (
    "口径：只统计已发布文章的正文；已剔除代码块、行内代码与链接；"
    "长度按「中日韩字符按字 + 拉丁按词」计；字组只在反复出现（≥3 次）时给出。"
)


@lru_cache(maxsize=1)
def load_corpus() -> tuple[SeedPost, ...]:
    """读一次种子语料并缓存（画像与问答共用同一份内容包口径）。"""
    return tuple(load_seed_posts())


def _samples_for(author_id: int, max_samples: int) -> list[SeedPost]:
    """取该作者的已发布文章。

    种子内容包只有已发布文章（草稿不入种子），因此这里没有额外的可见性过滤；
    **接上真实数据源时这一条必须显式写成 `status = published`** —— 草稿进画像
    等于把未发表内容泄露进提示词（红线 §7.3）。
    """
    owned = [post for post in load_corpus() if post.author_id == author_id]
    return owned[:max_samples]


@router.post("/writing/style", summary="写作风格画像（只量不写）", response_model=None)
async def style(request: WritingStyleRequest) -> WritingStyleResult:
    settings = StyleSettings(max_samples=request.max_samples)
    # 取样上限在两处生效（这里切、settings 也切）：**两边都要**，
    # 否则将来有人只改一处，就会出现「读了 50 篇但只统计 20 篇」或反之的静默分叉
    posts = _samples_for(request.author_id, request.max_samples)
    profile = build_style_profile(
        posts,
        tags_per_title=[post.tags for post in posts],
        settings=settings,
    )
    if profile is None:
        # 样本不够是**正常状态**（作者刚开始写），要给人话而不是一堆 0：
        # 0 与「没量」是两件事，混起来作者会以为自己的文章「没有风格」。
        # 顺便报出实际篇数与字数，作者一眼就知道还差多少 —— 「不足」两个字没有可操作性
        counted = sum(len(post.plain) for post in posts)
        notes = (
            f"该作者已发布 {len(posts)} 篇 / 约 {counted} 字，还不够量出画像"
            f"（需要 {settings.min_total_chars} 字以上）。{NOTES}"
        )
        logger.info("写作画像样本不足：author=%s posts=%d", request.author_id, len(posts))
        return WritingStyleResult(
            author_id=request.author_id,
            evidence_sufficient=False,
            profile=None,
            notes=notes,
        )

    logger.info(
        "写作画像完成：author=%s posts=%d chars=%d medianSentence=%s",
        request.author_id,
        profile.sample_count,
        profile.char_count,
        profile.median_sentence_chars,
    )
    return WritingStyleResult(
        author_id=request.author_id,
        evidence_sufficient=True,
        # dataclass → dict → 契约模型：字段名保持蛇形，出 JSON 时由别名生成器转驼峰。
        # **不做任何数值加工**（不四舍五入、不补字段）—— 加工一次，前端看到的就不是真实口径了
        profile=WritingStyleProfile.model_validate(asdict(profile)),
        notes=NOTES,
    )
