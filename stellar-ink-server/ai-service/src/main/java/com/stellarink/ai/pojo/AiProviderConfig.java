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

    private Integer enabled;

    private String lastCheckStatus;

    private String lastCheckMessage;

    private LocalDateTime lastCheckedAt;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
