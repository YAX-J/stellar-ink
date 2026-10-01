package com.stellarink.ai.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型供应商配置（表 {@code ai_provider_config}，按逻辑角色一行）。
 *
 * <p>归属：这是 AI 域自己的表，ai-service 拥有它、Python 只读它；
 * 不承载任何业务数据（用户、文章都不在这里）。
 *
 * <p>{@code apiKeyCipher} 存的是 AES-GCM 密文（见 {@code AesGcmCipher}），
 * 主密钥只在环境变量里 —— 所以**即使整库泄露，没有环境变量也解不开**。
 */
@Data
@TableName("ai_provider_config")
public class AiProviderConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑角色键：chat / fast / reasoning / embedding / rerank */
    private String role;

    /**
     * 这份生效配置来自模型库（{@code ai_model}）的哪一条；面板手填时为 null。
     *
     * <p>它不是外键（允许库里那条被删掉后这里留个悬空 id）：删库条目时行为是
     * 「只解绑、不动当前生效配置」，免得正在跑的能力突然取不到模型。
     */
    private Long modelId;

    /** 协议实现：openai_compatible / fake */
    private String provider;

    private String displayName;

    /** OpenAI 兼容端点，形如 https://api.deepseek.com/v1 */
    private String baseUrl;

    private String model;

    /** 加密后的 API Key；为空表示尚未配置 */
    private byte[] apiKeyCipher;

    /** 掩码（sk-…abcd），仅用于面板回显 */
    private String apiKeyMask;

    private Integer dimension;

    private Integer timeoutMs;

    private Integer maxTokens;

    private BigDecimal temperature;

    /**
     * 输入单价（元/百万 token）。空 = 未定价。
     *
     * <p>只用于「AI 调用账」（{@code ai_call_log}）算成本：记账时把当时的值**快照**进账里，
     * 所以事后改单价不会改写历史账目。为空的调用成本按「未知」处理，不会当 0 算。
     */
    private BigDecimal priceInputPerMillion;

    /** 输出单价（元/百万 token）。空 = 未定价。 */
    private BigDecimal priceOutputPerMillion;

    private Integer enabled;

    private String lastCheckStatus;

    private String lastCheckMessage;

    private LocalDateTime lastCheckedAt;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
