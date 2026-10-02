package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条作者记忆（表 {@code ai_memory}，脚本 {@code deploy/sql/16_ai_memory.sql}）。
 *
 * <p><b>幂等锚点是 {@code (user_id, memory_type, normalized)}</b>：同一用户、同一类型、
 * 归一化后同一句话只存一条。归一化在 Python 侧算好传进来，放在库里比对是为了让
 * 「重复确认」不会一天天把表撑大 —— 那看起来像「记性越来越好」。
 *
 * <p><b>状态四档而不是布尔</b>：{@code enabled=false} 分不清「用户暂时关了」与
 * 「用户要求删掉」，而这两件事在「要不要真的清理数据」上处置完全不同。
 */
@Data
@TableName("ai_memory")
public class AiMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户（业务库 user.id，本服务只读它、不 JOIN）。 */
    private Long userId;

    /** preference / fact / decision。 */
    private String memoryType;

    /** 记忆正文。 */
    private String content;

    /** 归一化正文：幂等锚点的一部分。 */
    private String normalized;

    /** 可信度（模型推测的封顶 0.7，用户确认可更高）。 */
    private java.math.BigDecimal confidence;

    /** model_suggested / user_stated / user_confirmed。 */
    private String source;

    /** pending / active / disabled / deleted。 */
    private String status;

    /** 用户确认时间（NULL = 还没被确认过）。 */
    private LocalDateTime confirmedAt;

    /** 过期时间（NULL = 不过期）。 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
