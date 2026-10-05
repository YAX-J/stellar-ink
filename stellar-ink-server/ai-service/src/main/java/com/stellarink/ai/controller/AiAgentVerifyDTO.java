package com.stellarink.ai.controller;

import com.stellarink.aiclient.dto.CitationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 面向浏览器的引用核验请求（{@code POST /ai/agent/verify}，A2）。
 *
 * <p>⚠️ **它刻意放在 ai-service 而不是 shared-model**：`shared-model` 不依赖
 * `stellar-ink-ai-client`（依赖方向是反的），而这个请求要用契约里的 {@code CitationDTO} ——
 * 搬进 shared-model 就得把那层依赖反过来，或者再抄一份引用形状。
 * 引用形状已经在契约里有一份，再抄一份就是「同一件事两个定义」。
 *
 * <p>只收「答案 + 引用」：**原文由 Python 侧从自己的语料取**，
 * 不接受浏览器传进来 —— 证据必须是服务端认的那一份，否则「片段与原文对不上」
 * 就退化成「调用方说原文是什么就是什么」。
 *
 * <p>{@code answer} **刻意不是 {@code @NotBlank}**：核验一个空答案是合法请求
 * （应当回答「没有编号、没有问题」），拦在门口等于让前端去猜结论。
 * 引用则要至少一条：一条都没有时核验没有任何输入，那种 {@code ok} 最容易被误读成「核过了」。
 */
@Data
public class AiAgentVerifyDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待核验的答案；长度与 Python 契约（20000 字）一致 */
    @Size(max = 20000, message = "答案过长")
    private String answer;

    /** 答案附带的引用清单：**就是答案后面那份**，顺序即编号 {@code [1] [2] …} */
    @NotEmpty(message = "citations 不能为空")
    @Size(max = 20, message = "一次最多核验 20 条引用")
    @Valid
    private List<CitationDTO> citations;
}
