package com.stellarink.ai.service;

import com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO;
import com.stellarink.sharedmodel.vo.ai.AiWikiBuildVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiClaimVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiEntityVO;

import java.util.List;

/**
 * LLM Wiki 的主张（E4-2）：抽取 → 落库 → 读者侧读取。
 *
 * <p>职责边界照旧：**算法在 Python**（怎么抽、怎么校验引用），
 * 这里只做「调一次 Python、把结果按幂等锚点写下来、再按文章读出来」。
 *
 * <p>为什么落库由 Java 做：`ai_*` 表归 ai-service，Python 不碰库 —— 这条边界从 M0 起没变过，
 * 变的只是这张表的内容（带证据的主张，而不是一段摘要）。
 */
public interface AiWikiService {

    /**
     * 抽一轮并落库，返回两边的账（抽取统计 + 落库统计）。
     *
     * @param request 给 Python 的抽取请求（预算已由控制器夹过上限）
     */
    AiWikiBuildVO build(AiWikiClaimsRequestDTO request);

    /** 按文章列主张（读者侧）：**按段落序号排序**，与文章里的顺序一致。 */
    List<AiWikiClaimVO> claimsOfPost(Long postId);

    /** 某篇文章当前有多少条主张（读者侧据此判断「这篇文章有没有 Wiki 条目」） */
    long countOfPost(Long postId);

    /**
     * 按文章列实体（读者侧，E4-7）：**只带本文的提及**与共现关系。
     *
     * <p>为什么按文章而不是给一个「全站实体列表」：实体页要能回答「它在这篇文章里是什么」。
     * 全站列表适合做索引（那是后面的「主题页面」），而现在读者是在读文章，
     * 顺手看到「本文提到的概念、以及它们和谁被一起谈论」才有用。
     *
     * <p>排序：提及多的在前，其次按名字 —— 顺序确定，否则同一份数据两次请求顺序不同。
     */
    List<AiWikiEntityVO> entitiesOfPost(Long postId);
}
