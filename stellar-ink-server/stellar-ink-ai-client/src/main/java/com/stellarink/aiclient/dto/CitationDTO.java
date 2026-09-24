package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 引用：必须能定位回原文的段落（M3 起由检索结果填充）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long postId;

    private String title;

    /** 段落序号，从 0 开始 */
    private Integer chunkIndex;

    /** 引用的原文片段 */
    private String snippet;

    /** 检索/重排得分；为空表示该来源未参与打分 */
    private Double score;
}
