package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条记忆的证据（表 {@code ai_memory_evidence}）。
 *
 * <p>它存在的理由就是 M9 的验收第三条：**模型不能在没有证据时把推测写成永久用户事实**。
 * 所以「记忆」与「证据」是两张表、一对多 —— 把出处塞进记忆行的一个字段里，
 * 就没法回答「这条记忆是靠哪几句话立起来的」。
 */
@Data
@TableName("ai_memory_evidence")
public class AiMemoryEvidence {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memoryId;

    /** quote（原文片段）/ user（用户确认）。 */
    private String kind;

    /** 原文片段或确认说明。 */
    private String ref;

    /** 相关文章（可为空：用户确认类证据不一定有文章）。 */
    private Long postId;

    private LocalDateTime createdAt;
}
