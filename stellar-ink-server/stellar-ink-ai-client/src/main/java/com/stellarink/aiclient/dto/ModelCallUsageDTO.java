package com.stellarink.aiclient.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/** 一次实际模型调用及其生效配置的价格快照，不包含密钥与用户正文。 */
@Data
@NoArgsConstructor
public class ModelCallUsageDTO {
    private String role;
    private String model;
    private String provider;
    private Long configId;
    private Long configUserId;
    private String configFingerprint;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer latencyMs;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private BigDecimal priceInput;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private BigDecimal priceOutput;
}
