package com.stellarink.sharedmodel.dto.ai;

import com.stellarink.sharedmodel.enums.AiModelRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 保存/更新一个逻辑角色的模型配置（{@code POST /ai/admin/providers}）。
 *
 * <p>{@code apiKey} 是**唯一的明文入口**：只在请求体里出现一次，落库前立即加密，
 * 之后任何接口都不会把它读回来（列表只给掩码）。留空表示「不改动已存的 Key」，
 * 这样前端可以只改模型名而不用重填密钥。
 */
@Data
public class AiProviderSaveDTO {

    @NotNull(message = "角色不能为空")
    private AiModelRole role;

    @NotBlank(message = "展示名不能为空")
    @Size(max = 64, message = "展示名不超过 64 字")
    private String displayName;

    /** 协议实现：目前只有 openai_compatible（fake 仅用于无密钥自测） */
    @Pattern(regexp = "openai_compatible|fake", message = "provider 只支持 openai_compatible 或 fake")
    private String provider = "openai_compatible";

    @NotBlank(message = "baseUrl 不能为空")
    @Size(max = 255, message = "baseUrl 过长")
    @Pattern(regexp = "https?://.+", message = "baseUrl 必须是 http(s) 地址")
    private String baseUrl;

    @NotBlank(message = "模型名不能为空")
    @Size(max = 128, message = "模型名不超过 128 字")
    private String model;

    /** 明文 API Key；留空表示沿用已存密钥（首次配置时必须给出） */
    @Size(max = 512, message = "API Key 过长")
    private String apiKey;

    /** 向量维度，embedding/rerank 角色必填，且换模型后必须与已建集合一致 */
    @Min(value = 1, message = "维度必须为正")
    @Max(value = 8192, message = "维度看起来不对")
    private Integer dimension;

    @Min(value = 1000, message = "超时至少 1 秒")
    @Max(value = 300000, message = "超时最多 5 分钟")
    private Integer timeoutMs = 30000;

    @Min(value = 1, message = "maxTokens 必须为正")
    @Max(value = 200000, message = "maxTokens 过大")
    private Integer maxTokens;

    /** 采样温度；为空表示用模型默认值 */
    private BigDecimal temperature;

    private Boolean enabled = Boolean.TRUE;
}
