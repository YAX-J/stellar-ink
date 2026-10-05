package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 确定性引用核验的请求（Python {@code AgentVerifyRequest}，A2）。
 *
 * <p>只传「答案 + 引用」两样：核验要用的**原文**由 Python 侧从自己的语料取，
 * 不接受调用方传进来 —— 否则「片段与原文对不上」就退化成「调用方说原文是什么就是什么」。
 *
 * <p>这个请求**不产出模型调用**（三条判定全是确定性的：编号越界 / 未标编号 / 片段对不上），
 * 因此它不计入调用账、也不占配额 —— 见 {@code AiAgentController.verify} 的说明。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentVerifyRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待核验的答案（通常就是 {@code /agent/ask} 或 {@code /qa} 返回的那段正文） */
    private String answer;

    /** 答案附带的引用清单：**就是答案后面那份**，顺序即编号 `[1] [2] …` */
    private List<CitationDTO> citations;
}
