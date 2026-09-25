package com.stellarink.sharedmodel.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
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
 *
 * <p>JSON 字面量用小写（{@code "chat"}），与 Python 契约、前端面板一致：
 * Java 枚举默认按**大写名字**序列化，不声明 {@code @JsonValue} 就会出现
 * 「前端传 "chat" → 反序列化失败 400」这种边界面不一致。
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

    /** 序列化为小写键：前端与 Python 都按这个字面量对接。 */
    @JsonValue
    public String getKey() {
        return key;
    }

    /**
     * 反序列化：接受小写键（{@code "chat"}）与枚举名（{@code "CHAT"}），
     * 两者都容忍前后空白；无法识别时抛异常（不静默回退到默认角色，否则会写错配置）。
     */
    @JsonCreator
    public static AiModelRole fromJson(String value) {
        AiModelRole parsed = parse(value);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "未知的模型角色：" + value + "，可选值 " + Arrays.toString(values()));
        }
        return parsed;
    }

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

    /**
     * 这个角色需要模型具备哪种能力。
     *
     * <p><b>必须与 Python 侧 {@code app/providers/registry.py} 的 {@code _ROLE_CAPABILITY} 一致</b>：
     * 面板按这里的结论过滤下拉框（把纯 chat 模型从 embedding 角色里排除掉），
     * 而 Python 在取实例时按它自己那份判断能力是否匹配。两边不一致的表现是
     * 「面板允许你选，选中后一问就报错」或反过来「明明能用的模型不让你选」。
     */
    public AiModelCapability capability() {
        return switch (this) {
            case EMBEDDING -> AiModelCapability.EMBEDDING;
            case RERANK -> AiModelCapability.RERANK;
            // chat / fast / reasoning 都要对话能力：它们是「用哪个档位的对话模型」的区别
            default -> AiModelCapability.CHAT;
        };
    }
}
