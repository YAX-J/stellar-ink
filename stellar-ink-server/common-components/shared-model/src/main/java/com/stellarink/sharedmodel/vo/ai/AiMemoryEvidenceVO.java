package com.stellarink.sharedmodel.vo.ai;

import lombok.Builder;
import lombok.Data;

/**
 * 一条记忆证据（M9）。
 *
 * <p>两种形态：{@code quote} 回到某段原文，{@code user} 是用户自己确认过。
 * 分开是有意的 —— 前者可以核对，后者只能算「用户说过」。
 */
@Data
@Builder
public class AiMemoryEvidenceVO {

    /** quote（原文片段）/ user（用户确认）。 */
    private String kind;

    /** 原文片段或确认说明。 */
    private String ref;

    /** 相关文章（可为空）。 */
    private Long postId;
}