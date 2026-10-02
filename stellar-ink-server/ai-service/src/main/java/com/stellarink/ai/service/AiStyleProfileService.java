package com.stellarink.ai.service;

import com.stellarink.sharedmodel.vo.ai.AiStyleProfileVO;

/**
 * 派生风格画像的落库（M9-3b）。
 *
 * <p><b>为什么它属于 M9 而不是 E1</b>：E1 的风格画像是**现算不落库**的只读统计量。
 * 到了 M9，roadmap 要求「删除记忆后同步清除向量、缓存与派生风格画像」——
 * 要能「清除」，就得先有**一份落库的、带版本的**画像，
 * 否则这句验收没有可验证的对象（清一个不存在的东西，看起来永远是对的）。
 *
 * <p>画像本身仍然只是统计量（句长 / 标点 / 关联词 / 反复字组），**不含原句** ——
 * 这条红线不因为落库而改变。
 */
public interface AiStyleProfileService {

    /**
     * 按作者刷新画像并存成新版本（版本号递增）。
     *
     * <p>为什么每次都存新版本而不是原地覆盖：画像会随新文章变化，
     * 而「什么时候变成这样的」在排查「AI 建议忽然不像我了」时是关键信息。
     *
     * @param userId   画像归属（登录者）
     * @param authorId 统计哪一位作者的文章（E1 的口径：按作者取样）
     */
    AiStyleProfileVO refresh(Long userId, Long authorId);

    /** 取最新一版画像；没有时返回 {@code null}（调用方要说「还没生成过」，而不是回一个空画像）。 */
    AiStyleProfileVO latest(Long userId);
}
