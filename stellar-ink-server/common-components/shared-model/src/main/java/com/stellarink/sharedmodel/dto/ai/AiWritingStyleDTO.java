package com.stellarink.sharedmodel.dto.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 面向浏览器的写作画像请求（{@code POST /ai/writing/style}）。
 *
 * <p>**没有 `authorId` 字段**，这是刻意的：要量谁的画像由 ai-service 从登录身份取。
 * 让客户端传 id 等于允许任何作者去量别人的写作习惯 —— 画像虽然不含原句，
 * 但「写了多少、什么时候写、爱用什么词」本身就是隐私。
 */
@Data
public class AiWritingStyleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 最多分析多少篇（可选，默认 20，上限与 Python 契约一致） */
    @Min(value = 1, message = "maxSamples 至少为 1")
    @Max(value = 50, message = "maxSamples 最多为 50")
    private Integer maxSamples;
}
