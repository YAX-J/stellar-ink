package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/** 算可召回集合（M9）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecallRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<MemoryRecordDTO> memories;

    private List<String> types;

    private Double minConfidence;

    private Integer limit;

    /** memoryId → 过期时间戳（毫秒）；缺键 = 不过期。 */
    private Map<Long, Long> expiresAtMs;
}