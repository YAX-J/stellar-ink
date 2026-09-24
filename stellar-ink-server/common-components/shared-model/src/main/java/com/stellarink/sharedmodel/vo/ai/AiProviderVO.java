package com.stellarink.sharedmodel.vo.ai;

import com.stellarink.sharedmodel.enums.AiModelRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置的**脱敏**视图（面板展示用）。
 *
 * <p>刻意不放任何可回读明文的字段：只有 {@code apiKeyMask}（形如 {@code sk-…9f3a}）
 * 与 {@code apiKeyConfigured} 布尔值。想看明文只有一条路 —— 重新填一次。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiProviderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private AiModelRole role;

    private String displayName;

    private String provider;

    private String baseUrl;

    private String model;

    /** 是否已配置密钥（前端据此决定是否提示「请填写 Key」） */
    private Boolean apiKeyConfigured;

    /** 密钥掩码，如 {@code sk-…9f3a}；未配置时为 null */
    private String apiKeyMask;

    private Integer dimension;

    private Integer timeoutMs;

    private Integer maxTokens;

    private BigDecimal temperature;

    private Boolean enabled;

    /** 最近一次连通性自检：ok / failed / unknown */
    private String lastCheckStatus;

    private String lastCheckMessage;

    private LocalDateTime lastCheckedAt;

    private LocalDateTime updatedAt;
}
