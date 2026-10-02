package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/** 与已有记忆冲突（M9）：两边正文都给出来，让人看着决定。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryConflictDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long memoryId;

    private String existingContent;

    private String candidateContent;
}