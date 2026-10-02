package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
/** 从一段对话里抽记忆候选（M9）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 对话原文（当前对话只是工作记忆，长度受限）。 */
    private String conversation;

    private Integer maxCandidates;

    /** model_suggested / user_stated。 */
    private String source;
}