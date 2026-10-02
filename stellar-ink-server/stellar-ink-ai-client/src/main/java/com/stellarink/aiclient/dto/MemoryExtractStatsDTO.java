package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/** 抽取的账（M9）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractStatsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer proposed;

    private Integer kept;

    /** 丢弃原因 → 条数（noEvidence / sensitive / badType / tooShort / tooLong …）。 */
    private Map<String, Integer> dropped;
}