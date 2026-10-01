package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 失效盘点结果（Python → Java，E4-11）。
 *
 * <p>三种状态**分开报**，因为处置不一样：
 * {@code stale}（段落内容变了）→ **重建**这几篇；{@code orphan}（段落已不存在）→ **清理**那些主张
 * （它们再也回不到原文了）；{@code current} → 不用动。
 *
 * <p>⚠️ 这份报告本身**不触发重建**：重建要花钱打模型，而报告是免费的。
 * 合成「自动重建」看起来更省事，代价是没人知道钱花在哪，而且一次误判
 * （比如语料缓存没刷新）会让它在后台反复烧钱。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiStaleResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer checked;

    private Integer current;

    private Integer stale;

    private Integer orphan;

    /** 需要重建的文章 */
    private List<Long> stalePostIds;

    /** 有失效引用的文章（需要清理主张，不一定需要重建） */
    private List<Long> orphanPostIds;

    /** 给人看的解释（例如「3 条主张引用的段落已经不存在」） */
    private List<String> notes;
}