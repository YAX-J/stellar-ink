package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 核验报告（Python {@code AgentVerifyResult}，A2）。
 *
 * <p>⚠️ {@code verdict=ok} 只表示**没查出问题**，不等于「这段答案是对的」：
 * 语义核验（论断有没有依据）还没做。所以 {@code checked} 必须与 verdict 一起显示
 * （「已核对 N 条引用」而不是「答案已核实”）——
 * 界面不得承诺服务端没算过的东西（与「不给不出会兑现的步数」同一条口径）。
 *
 * <p>{@code evidenceAvailable=false} 表示这次**没有原文可查**（{@code checked} 必然为 0）：
 * 前端那时要说「没能核对原文」，而不是「引用没问题」——
 * 「没查」与「查了没问题」是两件事。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentVerifyResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** {@code ok}（没查出问题）/ {@code warn}（查出问题）；Python 侧只出这两个值 */
    private String verdict;

    /** 回查到原文的引用条数：它是 {@code ok} 的可信度分母 */
    private Integer checked;

    /** 这次有没有原文可查；为 false 时 checked 必然为 0 */
    private Boolean evidenceAvailable;

    /** 答案里标出的引用编号（按出现顺序去重） */
    private List<Integer> citedIndexes;

    /** 其中越界的编号（指向不存在的引用） */
    private List<Integer> outOfRange;

    /** 有引用却一个编号都没标：这不等于「没有依据」 */
    private Boolean uncited;

    /** 可读的问题清单；为空即 {@code verdict=ok} */
    private List<AgentVerifyProblemDTO> problems;
}
