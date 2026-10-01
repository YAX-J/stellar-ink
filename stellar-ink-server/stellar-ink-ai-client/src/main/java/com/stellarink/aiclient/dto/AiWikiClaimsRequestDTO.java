package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 抽一轮 Wiki 主张的请求（Java → Python）。
 *
 * <p>{@code maxPosts} 是**成本闸门**：每篇文章一次模型调用，所以它有服务端上限
 * （{@code AiWikiController} 用 {@code bounded()} 取 min，客户端只能收紧 ——
 * 与 Agent 的预算同一条口径）。
 *
 * <p>{@code postIds} 给「**定向重建**」用（E4-11）：失效盘点说「这 3 篇该重建」之后，
 * 就按这个字段只重建这 3 篇。⚠️ 它与 {@code maxPosts} 是**两个不同的意图**
 * （「按顺序取几篇」vs「就要这几篇」）：同时传时以 {@code postIds} 为准、不再截断 ——
 * 否则会出现「报告说 3 篇要重建，实际重建的是头 5 篇里的 1 篇」这种查不出的现象。
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

    /** 定向重建：只抽这几篇（为空则按顺序取 maxPosts 篇） */
    private List<Long> postIds;
}