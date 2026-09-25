package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 策略摘要（{@code EvalStrategySummary}）：把列名还原成人类可读的开关组合。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalStrategySummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 策略 key（对比表列名） */
    private String key;

    /** 人类可读说明（例：{@code sparse+dense, rerank, candidateK=30}） */
    private String description;
}
