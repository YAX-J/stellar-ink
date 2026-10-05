package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一轮历史问答（Python 契约 {@code HistoryTurn}）。
 *
 * <p><b>它不是证据</b>：Python 的提示词明确要求只用它理解「这次追问在问什么」，
 * 不得当事实陈述、不得据它编号引用 —— 历史里装的是模型自己上一轮说过的话，
 * 当证据用等于让它拿自己的旧答案当出处。</p>
 *
 * <p>由浏览器回送（前端只送自己渲染过的那一份），Java 侧不加工、不补全。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaHistoryTurnDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 那一轮的问题 */
    private String question;

    /** 那一轮的回答 */
    private String answer;
}
