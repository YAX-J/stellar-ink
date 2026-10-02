package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/**
 * 写入计划的结果（M9）：三份清单分开，让「为什么没写进去」看得见。
 *
 * <p>{@code conflicts} 是刻意**不自动处理**的：措辞相近但正文不同，可能是同义改写、
 * 也可能是作者改了主意，两者都该由人决定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryPlanResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<MemoryCandidateDTO> toAdd;

    private List<MemoryDuplicateDTO> duplicates;

    private List<MemoryConflictDTO> conflicts;

    private List<String> notes;
}