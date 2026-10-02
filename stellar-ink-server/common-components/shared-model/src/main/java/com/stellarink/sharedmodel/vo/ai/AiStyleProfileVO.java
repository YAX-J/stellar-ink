package com.stellarink.sharedmodel.vo.ai;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一版派生风格画像（M9-3b）。
 *
 * <p>{@code version} 是必须露出来的：删记忆时要能说清「清掉的是哪一版」，
 * 而 {@code profile} 里只有统计量、**不含原句**（与 E1 同一条红线）。
 */
@Data
@Builder
public class AiStyleProfileVO {

    private Long id;

    private Long userId;

    /** 版本号（从 1 递增）。 */
    private Integer version;

    /** 可解释的风格特征 JSON 文本（句长 / 标点 / 关联词 / 反复字组…）。 */
    private String profile;

    private LocalDateTime createdAt;
}
