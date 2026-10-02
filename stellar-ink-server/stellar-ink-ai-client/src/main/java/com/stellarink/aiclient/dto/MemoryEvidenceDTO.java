package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
/** 一条记忆证据（M9）：回到某段原文，或用户自己确认过。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEvidenceDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** quote（原文片段）/ user（用户确认）。 */
    private String kind;

    /** 原文片段或确认说明。 */
    private String ref;

    /** 相关文章（可为空）。 */
    private Long postId;
}