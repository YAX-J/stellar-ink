package com.stellarink.ai.service;

import com.stellarink.aiclient.dto.CitationDTO;
import com.stellarink.sharedmodel.vo.ai.AiRetrievalAuditSummaryVO;

import java.util.List;

/**
 * 检索审计（M8）：把线上每次检索的**规模与结果**记下来。
 *
 * <p>它补的是进程内回放（E3-4）补不了的那一半：回放能看「这一台、这一会儿」的候选全文，
 * 但看不到「最近七天哪类问题总被拒答」—— 后者要落库、要能按时间与场景聚合。
 *
 * <p>三条口径：
 *
 * <ol>
 *   <li><b>不存问题原文</b>：存 SHA-256 与长度。要看原文按 traceId 去回放查
 *       （那份有内容但不落库）；同一个问题问过几次、是不是复现性问题，哈希就够了。</li>
 *   <li><b>记录必须 best-effort</b>：审计写失败**绝不能**影响这次问答 ——
 *       用户问一个问题，不该因为「审计表写不进去」而拿不到答案（与「辅助信息失败不损伤主流程」同源）。
 *       失败只记一条 warn。</li>
 *   <li><b>失败也要留痕</b>：调用失败时同样写一行（`failed=true`）——
 *       不记失败的话，「失败率」永远算不出来，而失败率是这张表最有用的一个数。</li>
 * </ol>
 */
public interface AiRetrievalAuditService {

    /**
     * 记一次检索。
     *
     * @param scene      场景（qa / qa_stream / agent / eval）
     * @param question   问题原文（**只用来算哈希与长度**，不落库）
     * @param citations  最终进答案的引用（文章 id 与分数从这里来）
     * @param candidates 召回候选数（0 表示没记到，与「真的没有候选」在表里都记 0）
     * @param failed     这次调用是否失败
     */
    void record(Long userId, String scene, String question, List<CitationDTO> citations,
                int candidates, boolean failed, Integer latencyMs, String model);

    /** 最近若干天的汇总（给 ADMIN 看：拒答率、失败率、被引用最多的文章）。 */
    AiRetrievalAuditSummaryVO summarize(int days);
}
