package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 派生风格画像（表 {@code ai_style_profile}，脚本 {@code deploy/sql/16_ai_memory.sql}）。
 *
 * <p><b>它是派生数据</b>：从作者自己的文章统计出来，不是用户直接写的东西。
 * 所以删除记忆时必须连它一起清（M9 验收：删除后同步清除向量、缓存与派生画像）——
 * 否则「删除」之后，画像里还留着从那些记忆推出来的特征。
 *
 * <p><b>必须带版本号</b>：有版本才能说清「清掉的是哪一版」，
 * 而不是让人对着一行没有版本的数据猜它是哪次算出来的。
 */
@Data
@TableName("ai_style_profile")
public class AiStyleProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 画像版本（从 1 递增）。 */
    private Integer version;

    /** 可解释的风格特征 JSON（**不含原句**）。 */
    private String payload;

    private LocalDateTime createdAt;
}
