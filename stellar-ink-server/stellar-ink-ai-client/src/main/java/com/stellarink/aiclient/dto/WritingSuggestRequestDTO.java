package com.stellarink.aiclient.dto;

import com.stellarink.aiclient.enums.WritingTask;
import com.stellarink.aiclient.enums.WritingTone;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Python 写作建议契约 {@code WritingSuggestRequest} 的 Java 侧对应物。
 *
 * <p>草稿随本次请求传输，**不进公共索引**（红线 §7.3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingSuggestRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private WritingTask task;

    /** 当前草稿正文（不超过 20000 字） */
    private String draft;

    /** 附加要求（可选），与 tone 同时存在时以它为准 */
    private String instruction;

    private WritingTone tone;

    /** 期望候选数上限（1-5） */
    private Integer candidateCount;
}
