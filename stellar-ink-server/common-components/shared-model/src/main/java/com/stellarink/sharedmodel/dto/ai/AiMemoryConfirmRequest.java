package com.stellarink.sharedmodel.dto.ai;

import lombok.Data;

import java.util.List;

/**
 * 确认记忆的请求（M9）。
 *
 * <p>{@code memoryIds} 为空 = 确认**全部**待确认的记忆。
 * 支持只确认一部分是有用的：前端可以逐条确认，而不是全有全无。
 */
@Data
public class AiMemoryConfirmRequest {

    /** 只确认这几条（为空 = 全部待确认）。 */
    private List<Long> memoryIds;
}
