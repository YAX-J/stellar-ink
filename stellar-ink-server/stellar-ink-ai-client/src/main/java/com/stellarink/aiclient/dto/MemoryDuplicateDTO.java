package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/** 与已有记忆重复（M9）：{@code enriched=true} 表示它带来了库里没有的新证据（建议补证据，不改正文）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryDuplicateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long memoryId;

    private String content;

    private Boolean enriched;
}