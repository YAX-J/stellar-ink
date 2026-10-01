package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 失效盘点结果（E4-11，给 ADMIN 看）。
 *
 * <p>三块信息缺一不可：**计数**（有多少条不对劲）、**要动的文章**（下一步点哪些去重建）、
 * **给人看的话**（「引用的段落已经不存在」这种状态含义）。
 * 只回计数的话，运维看到「stale 3」不知道下一步做什么。
 *
 * <p>⚠️ 这份报告**不触发重建**：重建要花钱打模型，报告是免费的。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiStaleVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer checked;

    private Integer current;

    private Integer stale;

    private Integer orphan;

    /** 需要重建的文章（可以直接拿去 `POST /ai/admin/wiki/build` 的 `postIds`） */
    private List<Long> stalePostIds;

    /** 有失效引用的文章（主张要清理：它们再也回不到原文了） */
    private List<Long> orphanPostIds;

    private List<String> notes;

    /** 这次盘点看了多少条主张、有没有被上限截断（截断了就必须说，否则报告看起来是「全站没问题」） */
    private Boolean truncated;
}
