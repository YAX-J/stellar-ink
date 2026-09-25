package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 写作画像结果（Python {@code WritingStyleResult}）。
 *
 * <p>`evidenceSufficient=false` 表示**样本不够**（作者还没写够），此时 `profile` 为空。
 * 前端要据此显示一句人话，而不是把 null 当成「没有风格」或者画一堆 0 ——
 * 0 与「没量」是两件事。`notes` 里带着口径与「还差多少字」，直接可展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingStyleResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long authorId;

    private Boolean evidenceSufficient;

    private WritingStyleProfileDTO profile;

    /** 口径说明 / 样本不足的可读原因 */
    private String notes;
}
