package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Python 问答契约 {@code QaStreamRequest} 的 Java 侧对应物。
 *
 * <p>这是**内部协议**：只由 ai-service 发往 Python，不出现在对浏览器的接口文档里。
 * 面向浏览器的请求体在 {@code shared-model} 的 {@code dto/ai} 下单独定义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaStreamRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户问题（不超过 500 字，与 Python 契约同口径） */
    private String question;

    /**
     * 这位作者的长期记忆（M9，最多几条由 Python 侧封顶）。
     *
     * <p><b>它不是文章内容</b>：Python 的提示词明确要求只用它调整语气与取舍，
     * 不得当事实陈述、不得编号引用 —— 否则「作者喜欢短句」会被写成「文章里说他喜欢短句」。
     *
     * <p>由 Java 按登录身份取好再传：Python 不碰库，而**取数范围就是用户隔离**。
     */
    private java.util.List<String> memories;

    /** 多轮会话标识；为空表示一次性提问 */
    private String conversationId;

    /** 召回候选数上限（1-20） */
    private Integer topK;
}
