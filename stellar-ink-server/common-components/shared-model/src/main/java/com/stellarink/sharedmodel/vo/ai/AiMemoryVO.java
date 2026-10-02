package com.stellarink.sharedmodel.vo.ai;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条作者记忆（M9，前端管理面板用）。
 *
 * <p><b>证据必须一起回给前端</b>：记忆面板上「凭什么记住这条」和「这条是什么」同样重要 ——
 * 只给正文的话，用户唯一能做的判断就是「信不信」，而没有依据可以核对。
 */
@Data
@Builder
public class AiMemoryVO {

    private Long id;

    /** preference / fact / decision。 */
    private String memoryType;

    private String content;

    /** 0-1 的可信度。 */
    private Double confidence;

    /** model_suggested / user_stated / user_confirmed。 */
    private String source;

    /** pending / active / disabled / deleted。 */
    private String status;

    /** 用户确认时间（NULL = 还没被确认过）。 */
    private LocalDateTime confirmedAt;

    /** 过期时间（NULL = 不过期）。 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private List<AiMemoryEvidenceVO> evidence;
}