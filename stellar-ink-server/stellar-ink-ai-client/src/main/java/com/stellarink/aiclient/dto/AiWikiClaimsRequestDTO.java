package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 抽一轮 Wiki 主张的请求（Java → Python）。
 *
 * <p>{@code maxPosts} 是**成本闸门**：每篇文章一次模型调用，所以它有服务端上限
 * （{@code AiWikiController} 用 {@code bounded()} 取 min，客户端只能收紧 ——
 * 与 Agent 的预算同一条口径）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiClaimsRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer maxPosts;

    private Integer maxClaimsPerChunk;
}
