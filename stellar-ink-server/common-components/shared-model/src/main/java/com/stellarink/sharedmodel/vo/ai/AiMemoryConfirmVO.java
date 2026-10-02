package com.stellarink.sharedmodel.vo.ai;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 确认结果（M9）：把「写进去了几条、合并了几条、哪几条需要你决定」说清楚。
 *
 * <p>{@code conflicts} 必须回给前端：它是**没被处理**的那部分，
 * 前端若只显示「已保存 N 条」，用户会以为冲突的候选也被接受了。
 */
@Data
@Builder
public class AiMemoryConfirmVO {

    /** 新增为生效记忆（active）的条数。 */
    private Integer added;

    /** 与已有记忆重复、已合并证据的条数。 */
    private Integer merged;

    /** 需要用户决定的冲突（保持 pending，未生效）。 */
    private List<AiMemoryConflictVO> conflicts;

    private List<String> notes;
}