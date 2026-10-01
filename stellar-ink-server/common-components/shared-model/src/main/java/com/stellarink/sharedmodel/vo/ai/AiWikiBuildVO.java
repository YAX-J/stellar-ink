package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 一轮 Wiki 构建的结果（E4-2）：抽了多少、写库多少、丢了多少。
 *
 * <p>四个数字缺一不可，因为它们回答的是**不同**的问题：
 * <ul>
 *   <li>{@code proposed} / {@code dropped}：模型提了多少、被证据校验挡掉多少
 *       —— 「抽出来很少」是模型不行还是校验太严，全看这两个；</li>
 *   <li>{@code inserted} / {@code updated}：这次**真的写进库**多少、更新了多少
 *       —— 重复构建时它们会告诉你「有没有变」，而不是每次都显示「新增 3 条」；</li>
 *   <li>{@code skipped}：Python 留下的主张里，有多少因为库里已有同版本同文本而没动。</li>
 * </ul>
 *
 * <p>把「抽取统计」与「落库统计」分开挂在一个响应里，而不是各回各的：
 * 一次构建的动作是一体的，分两次看必然有人只看一半。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiBuildVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Python 侧实际抽了几篇文章 */
    private Integer posts;

    /** 模型提出的主张条数（含被丢弃的） */
    private Integer proposed;

    /** 通过证据校验、被 Python 留下的条数 */
    private Integer kept;

    /** 落入新记录数 */
    private Integer inserted;

    /** 命中幂等锚点、更新了内容的条数 */
    private Integer updated;

    /** 已存在且内容一致、未改动的条数 */
    private Integer skipped;

    /** 丢弃原因 → 条数 */
    private Map<String, Integer> dropped;

    /** 合并后的实体个数（E4-4）—— 实体是后面「关系」与「主题页面」的骨架 */
    private Integer entities;

    /** 模型提出多少次实体、通过证据校验多少次（「实体也必须有证据」要看得见） */
    private Integer entityProposed;

    private Integer entityKept;

    /** 实体之间的共现关系条数（E4-5）—— 如实叫「共现」，不说成「因果关系」 */
    private Integer relations;

    /** 主题个数（E4-8）：共现图上的连通分量，主题页的原料 */
    private Integer topics;

    private String usageModel;

    private Long latencyMs;

    /** 给人看的提示（含 Python 侧的解释，例如「N 条因引用找不到原文依据被丢弃」） */
    private List<String> notes;
}
