package com.stellarink.sharedmodel.vo.ai;

import com.stellarink.sharedmodel.enums.AiModelCapability;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型库里一条模型的**脱敏**视图（面板展示 + 下拉框数据源）。
 *
 * <p>同样不放任何可回读明文的字段：只有 {@code apiKeyMask} 与 {@code apiKeyConfigured}。
 *
 * <p>{@code boundRoles} 是刻意的：面板要能直接显示「这个模型正被 chat 使用」，
 * 否则删一条正在用的模型不会有人拦你 —— 而删掉之后那个角色会在下一次问答时才发现取不到模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModelVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String displayName;

    private String provider;

    private String baseUrl;

    private String model;

    private Boolean apiKeyConfigured;

    /** 密钥掩码，如 {@code sk-…9f3a}；未配置时为 null */
    private String apiKeyMask;

    /** 能干什么（决定它会出现在哪些角色的下拉框里） */
    private List<AiModelCapability> capabilities;

    private Integer dimension;

    private Integer timeoutMs;

    private Integer maxTokens;

    private BigDecimal temperature;

    private Boolean enabled;

    /** 正被哪些角色使用（角色键，如 {@code ["chat"]}）；空数组表示还没人用 */
    private List<String> boundRoles;

    private String lastCheckStatus;

    private String lastCheckMessage;

    private LocalDateTime lastCheckedAt;

    private LocalDateTime updatedAt;
}
