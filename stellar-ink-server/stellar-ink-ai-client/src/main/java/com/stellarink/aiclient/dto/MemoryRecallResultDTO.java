package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/** 可召回的记忆 id（M9，已按可信度与新旧排序）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecallResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<Long> memoryIds;

    private List<String> notes;
}