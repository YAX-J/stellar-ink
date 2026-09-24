package com.stellarink.sharedmodel.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * AI 逻辑角色：一份模型配置对应一个角色，业务代码按角色取模型，不写死厂商或模型名。
 *
 * <p>为什么要分角色而不是「一个模型走天下」：简单任务用便宜快的模型、复杂推理用强模型、
 * 向量化必须用专用 embedding 模型 —— 这三类能力、价格与延迟差异很大，
 * 混成一个配置会逼着业务代码在调用处判断「这次该用哪个」。
 */
@Getter
@AllArgsConstructor
public enum AiModelRole {

    /** 通用对话：问答、摘要、润色 */
    CHAT("chat", "对话模型"),

    /** 轻量任务：分类、打标签、意图识别 */
    FAST("fast", "快速模型"),

    /** 复杂推理：多步分析、Deep Research */
    REASONING("reasoning", "推理模型"),

    /** 向量化：文章切块嵌入 */
    EMBEDDING("embedding", "嵌入模型"),

    /** 重排：检索候选精排（M4 起使用） */
    RERANK("rerank", "重排模型");

    /** 存储与接口里用的键（小写，与 Python 侧枚举逐字一致） */
    private final String key;

    private final String label;

    /** 解析角色键；无法识别时返回 {@code null}（由上层决定报错还是忽略）。 */
    public static AiModelRole parse(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.key.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
