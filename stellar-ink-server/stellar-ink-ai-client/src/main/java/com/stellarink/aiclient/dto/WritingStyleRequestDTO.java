package com.stellarink.aiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 写作画像请求（Python {@code WritingStyleRequest}）。
 *
 * <p>`authorId` 由 ai-service 从登录身份取，**不接受客户端传值** ——
 * 否则任何人都能拿别人的 id 去量画像（虽然画像不含原句，但「写了多少、什么时候写」也是隐私）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingStyleRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long authorId;

    /**
     * 最多分析多少篇。**必须有默认值**：调用方（{@code AiStyleProfileServiceImpl}）只设 authorId，
     * 而 Jackson 默认会把没设的包装类型序列化成 null —— Python 侧的 {@code max_samples: int}
     * 不接受 null，于是整条链路变成 422 → 「系统繁忙」（实测：这就是 /ai/writing/style 500 的原因）。
     * 注意这里**不能**靠 {@code @JsonInclude(NON_NULL)} 之类「不序列化 null」的办法：契约 fixture 里
     * 就是带 null 的，改序列化会让 AiContractTest 直接红。
     */
    @Builder.Default
    private Integer maxSamples = 20;

    /** 序列化前的兜底：缺省时给契约默认值（20），避免 Python 侧看到 null。 */
    @JsonIgnore
    public int resolvedMaxSamples() {
        return maxSamples == null ? 20 : maxSamples;
    }
}
