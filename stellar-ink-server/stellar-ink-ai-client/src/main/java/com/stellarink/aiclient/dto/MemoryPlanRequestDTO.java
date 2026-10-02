package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/** 算写入计划（M9）：已有记忆 + 候选。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryPlanRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<MemoryRecordDTO> existing;

    private List<MemoryCandidateDTO> candidates;
}