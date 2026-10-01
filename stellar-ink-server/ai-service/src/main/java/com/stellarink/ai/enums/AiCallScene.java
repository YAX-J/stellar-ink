package com.stellarink.ai.enums;

/**
 * AI 调用场景：一条账属于哪条业务路径。
 *
 * <p>为什么用枚举而不是散落的字符串字面量：{@code scene} 是账表里**唯一的分组维度之一**，
 * 写错一个字母（{@code qa} 写成 {@code QA} / {@code qa-stream}）不会报错，
 * 只会让成本看板上多出一行谁也不认识的场景、而原场景的数字凭空变少。
 *
 * <p>{@code providerRole} 是这条场景固定会用到的模型角色（Python 侧的逻辑角色）：
 * 记账时按它去角色配置里取**单价快照**；评测一次要跑嵌入与重排多个模型，故为 {@code null}。
 */
public enum AiCallScene {

    /** 星海问答（非流式，深读页「问星笺」的降级路径与首屏入口） */
    QA("qa", "chat"),

    /** 星海问答（SSE 流式）：用量在 {@code done} 帧里回来，故由流式出口单独记账 */
    QA_STREAM("qa_stream", "chat"),

    /** 执笔页 Copilot 建议（只给候选，不写正文） */
    WRITING_SUGGEST("writing_suggest", "chat"),

    /** 只读 Agent（可能多步调用模型，但账只记一次调用入口） */
    AGENT("agent", "chat"),

    /** 评测台跑一轮：一次包含嵌入与重排，token 用量目前不由 Python 回报，故为空 */
    EVAL("eval", null);

    private final String code;

    private final String providerRole;

    AiCallScene(String code, String providerRole) {
        this.code = code;
        this.providerRole = providerRole;
    }

    public String code() {
        return code;
    }

    /** 固定会用到的模型角色；评测等多模型场景为 {@code null} */
    public String providerRole() {
        return providerRole;
    }
}
