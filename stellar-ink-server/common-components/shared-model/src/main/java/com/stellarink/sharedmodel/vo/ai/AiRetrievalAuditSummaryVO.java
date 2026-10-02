package com.stellarink.sharedmodel.vo.ai;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 检索审计的汇总（M8，ADMIN 看）。
 *
 * <p>四个数各有各的用处，混在一起就没用了：
 *
 * <ul>
 *   <li>{@code total} / {@code refused} / {@code failed}：<b>拒答率与失败率</b> ——
 *       拒答率高说明语料覆盖不够，失败率高说明链路有问题，两者的处置完全不同；</li>
 *   <li>{@code topPosts}：被引用最多的文章（哪几篇在撑场面）；</li>
 *   <li>{@code repeatQuestions}：**被反复问的问题**（哈希相同）——
 *       它们是「这个知识点该补一篇」的最直接信号。</li>
 * </ul>
 */
@Data
@Builder
public class AiRetrievalAuditSummaryVO {

    private Integer days;

    private Integer total;

    private Integer refused;

    private Integer failed;

    /** 拒答率（0-1，保留三位）。 */
    private Double refusalRate;

    /** 失败率（0-1，保留三位）。 */
    private Double failureRate;

    /** 被引用最多的文章：postId → 次数。 */
    private Map<Long, Integer> topPosts;

    /** 被反复问的问题（哈希 → 次数），只列问过两次以上的。 */
    private Map<String, Integer> repeatQuestions;

    private List<String> notes;
}
