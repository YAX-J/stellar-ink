package com.stellarink.sharedmodel.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;

/**
 * 一个模型**能干什么**：对话 / 嵌入 / 重排。
 *
 * <p>为什么模型库里要显式标注能力，而不是像以前那样「角色即能力」：
 * 以前一个角色一行配置，选了 {@code embedding} 角色就等于声明「这是嵌入模型」。
 * 现在模型库与角色是两张表，同一批模型会出现在不同角色的下拉框里，
 * 必须有个地方说清「这个模型能不能干这件事」——否则把一个纯 chat 模型绑到
 * {@code embedding} 角色上，直到真正调用才报错，而那时人已经离开了配置页。
 *
 * <p>JSON 字面量用小写（与 Python 的 {@code ProviderCapabilities}、前端面板一致）。
 */
public enum AiModelCapability {

    CHAT("chat"),

    EMBEDDING("embedding"),

    RERANK("rerank");

    private final String key;

    AiModelCapability(String key) {
        this.key = key;
    }

    /** 存储与接口里用的键（小写） */
    @JsonValue
    public String getKey() {
        return key;
    }

    @JsonCreator
    public static AiModelCapability fromJson(String value) {
        AiModelCapability parsed = parse(value);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "未知的模型能力：" + value + "，可选值 " + Arrays.toString(values()));
        }
        return parsed;
    }

    /** 解析能力键；无法识别时返回 {@code null}（由上层决定报错还是忽略）。 */
    public static AiModelCapability parse(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(capability -> capability.key.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
