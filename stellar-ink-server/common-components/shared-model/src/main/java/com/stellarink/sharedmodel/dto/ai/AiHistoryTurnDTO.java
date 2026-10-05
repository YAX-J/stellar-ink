package com.stellarink.sharedmodel.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 多轮会话里的一轮问答（面向浏览器的形态，{@code POST /ai/qa} 与 {@code /ai/qa/stream}）。
 *
 * <p><b>它不是证据</b>：Python 侧的提示词明确要求只用它理解「这次追问在问什么」
 * （「那它呢」「上面那个报错」指的是哪件事），不得当事实陈述、不得据它编号引用。
 * 历史里装的是模型自己上一轮说过的话 —— 当证据用等于让它拿自己的旧答案当出处。</p>
 *
 * <p>由浏览器回送（前端只送自己渲染过的那一份），服务端不补全、不推断。</p>
 */
@Data
public class AiHistoryTurnDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "历史里的问题不能为空")
    @Size(max = 500, message = "历史里的问题不能超过 500 字")
    private String question;

    @NotBlank(message = "历史里的回答不能为空")
    @Size(max = 2000, message = "历史里的回答不能超过 2000 字")
    private String answer;
}
