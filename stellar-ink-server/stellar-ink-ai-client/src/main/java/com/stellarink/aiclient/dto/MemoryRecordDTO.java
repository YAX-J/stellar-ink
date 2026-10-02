package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/** 库里已有的一条记忆（M9，规划写入时的输入）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long memoryId;

    private String type;

    private String content;

    private Double confidence;

    /** pending / active / disabled / deleted。 */
    private String status;

    private List<MemoryEvidenceDTO> evidence;
}