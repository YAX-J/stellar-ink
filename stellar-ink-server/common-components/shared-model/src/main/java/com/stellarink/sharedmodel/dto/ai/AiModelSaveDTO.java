package com.stellarink.sharedmodel.dto.ai;

import com.stellarink.sharedmodel.enums.AiModelCapability;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Set;

/**
 * 往模型库里新增/修改一个模型。
 *
 * <p>两个刻意的约定：
 * <ul>
 *   <li>{@code id} 为空表示新建；非空表示修改那一条（库里可以有多个 chat 模型，靠 id 区分）；</li>
 *   <li>{@code apiKey} 留空表示**沿用已存密钥** —— 改个名字或超时不必重填 Key，
 *       而「重填」是唯一能覆盖旧 Key 的方式（没有任何接口能读回明文）。</li>
 * </ul>
 */
@Data
public class AiModelSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 为空 = 新建；非空 = 修改指定条目 */
    private Long id;

    @NotBlank(message = "模型展示名不能为空")
    @Size(max = 64, message = "展示名最长 64 个字符")
    private String displayName;

    @NotBlank(message = "协议不能为空")
    @Size(max = 32, message = "协议最长 32 个字符")
    private String provider;

    @NotBlank(message = "端点不能为空")
    @Size(max = 255, message = "端点最长 255 个字符")
    private String baseUrl;

    @NotBlank(message = "模型名不能为空")
    @Size(max = 128, message = "模型名最长 128 个字符")
    private String model;

    /** 明文 API Key；留空表示沿用已存密钥（首次配置时必填） */
    private String apiKey;

    /** 这个模型能干什么；至少要标一个，否则它不会出现在任何角色的下拉框里 */
    @NotEmpty(message = "至少要勾选一种能力（对话/嵌入/重排）")
    private Set<AiModelCapability> capabilities;

    @Min(value = 1, message = "向量维度必须为正")
    @Max(value = 65536, message = "向量维度超出合理范围")
    private Integer dimension;

    @Min(value = 100, message = "超时不能小于 100ms")
    @Max(value = 600000, message = "超时不能大于 10 分钟")
    private Integer timeoutMs;

    @Min(value = 1, message = "maxTokens 必须为正")
    private Integer maxTokens;

    private BigDecimal temperature;

    private Boolean enabled;
}
