package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型库里的一条模型（表 {@code ai_model}）。
 *
 * <p>与 {@link AiProviderConfig} 的分工：这张表是**素材库**（可以有很多 chat 模型），
 * 那张表是**每个角色当前生效的配置**（一个角色一行）。角色绑定某条模型时，
 * 字段会被复制进角色行 —— Python 只读角色表，因此它不需要知道模型库的存在。
 *
 * <p>能力用三个 TINYINT 列而不是一个逗号串：SQL 里能直接筛（{@code idx_capability}），
 * 也不需要在 Java/Python 两侧各写一遍字符串解析。
 */
@Data
@TableName("ai_model")
public class AiModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String displayName;

    /** 协议实现：openai_compatible / fake */
    private String provider;

    private String baseUrl;

    private String model;

    /** 加密后的 API Key；为空表示尚未配置 */
    private byte[] apiKeyCipher;

    /** 掩码（sk-…abcd），仅用于面板回显 */
    private String apiKeyMask;

    private Integer capChat;

    private Integer capEmbedding;

    private Integer capRerank;

    private Integer dimension;

    private Integer timeoutMs;

    private Integer maxTokens;

    private BigDecimal temperature;

    private Integer enabled;

    private String lastCheckStatus;

    private String lastCheckMessage;

    private LocalDateTime lastCheckedAt;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
