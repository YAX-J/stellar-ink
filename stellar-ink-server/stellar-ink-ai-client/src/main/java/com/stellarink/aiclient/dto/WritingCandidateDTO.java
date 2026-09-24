package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单个写作候选。
 *
 * <p>边界：建议**不直接写正文**，由前端差异预览 + 作者确认后走既有 {@code /posts/**} 接口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingCandidateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String text;

    /** 面向作者的中文理由，可空 */
    private String rationale;
}
