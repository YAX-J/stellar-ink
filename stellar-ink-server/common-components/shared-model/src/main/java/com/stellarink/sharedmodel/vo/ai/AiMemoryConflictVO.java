package com.stellarink.sharedmodel.vo.ai;

import lombok.Builder;
import lombok.Data;

/** 一条冲突（M9）：两边正文都给出来，让人看着决定保留哪边。 */
@Data
@Builder
public class AiMemoryConflictVO {

    /** 已生效的那条记忆 id。 */
    private Long memoryId;

    private String existingContent;

    /** 待确认那条的正文（仍在 pending）。 */
    private String candidateContent;

    /** 待确认那条的 id（用户要「用新的替换」时需要它）。 */
    private Long candidateId;
}