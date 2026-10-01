package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Python 侧抽出来的带证据主张（{@code POST /wiki/claims}，E4-1/E4-2）。
 *
 * <p>契约样例是两侧共读的 {@code tests/fixtures/wiki_claims_result.json}
 * （由 {@code scripts/gen_wiki_fixture.py} 真跑一遍抽取生成，里面故意含一条**被丢弃**的主张）。
 *
 * <p><b>{@code stats} 与 {@code claims} 同等重要</b>：只回主张列表的话，调用方看不到
 * 「提了 4 条、被校验挡掉 1 条」，而那个比例正是判断「这套抽取能不能用」的关键 ——
 * 也是区分「模型不行」与「引用编造被挡下」的唯一线索。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiClaimsResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<AiWikiClaimDTO> claims;

    /** 合并后的实体（E4-4）：按出现次数排序，每个提及都能回到某条主张 */
    private List<AiWikiEntityDTO> entities;

    private AiWikiStatsDTO stats;

    /** 给人看的提示（例如「N 条因引用找不到原文依据被丢弃」） */
    private List<String> notes;

    private String usageModel;

    private Long latencyMs;

    /** 丢弃原因 → 条数（{@code quoteNotFound} / {@code unknownChunk} / …） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiWikiStatsDTO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Integer proposed;

        private Integer kept;

        private Map<String, Integer> dropped;

        private Integer posts;

        /** 实体：模型提出多少次、通过证据校验多少次、合并成几个 */
        private Integer entityProposed;

        private Integer entityKept;

        private Integer entities;
    }
}
