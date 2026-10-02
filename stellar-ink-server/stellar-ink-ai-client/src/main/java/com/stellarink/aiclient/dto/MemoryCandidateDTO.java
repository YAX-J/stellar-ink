package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
/** 一条记忆候选（M9）：**还没落库**的东西。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryCandidateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** preference / fact / decision。 */
    private String type;

    private String content;

    /**
     * 归一化正文（全角/大小写/空白抹平）。
     *
     * <p>由 **Python 侧算**并回传：Java 不重复实现一遍归一化 ——
     * 两份实现早晚会分叉，而分叉的表现是「同一条记忆存了两行」。
     */
    private String normalized;

    private Double confidence;

    /** model_suggested / user_stated / user_confirmed。 */
    private String source;

    private List<MemoryEvidenceDTO> evidence;
}