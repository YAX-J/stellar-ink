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

    private Integer maxSamples;

    /** 序列化前的兜底：缺省时给契约默认值（20），避免 Python 侧看到 null。 */
    @JsonIgnore
    public int resolvedMaxSamples() {
        return maxSamples == null ? 20 : maxSamples;
    }
}
