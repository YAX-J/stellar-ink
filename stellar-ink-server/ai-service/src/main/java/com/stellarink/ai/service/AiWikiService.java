package com.stellarink.ai.service;

import com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO;
import com.stellarink.sharedmodel.vo.ai.AiWikiBuildVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiClaimVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiEntityVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiStaleVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiTopicVO;

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

    /**
     * 按文章列主题（读者侧，E4-9）：只回**涉及这篇文章**的主题。
     *
     * <p>为什么按文章过滤而不是给一个「全站主题列表」：读者是在读文章，
     * 「这篇文章参与了哪些主题」才是当下有用的信息；全站索引是另一件事（列表页）。
     * 排序：权重降序、名字升序 —— 顺序确定。
     */
    List<AiWikiTopicVO> topicsOfPost(Long postId);

    /**
     * 失效盘点（E4-11）：把库里存的主张锚点交给 Python 比对当前语料。
     *
     * <p>为什么要问 Python：段落序号与内容哈希都是**切块的产物**，只有它知道当前是哪一版。
     * Java 只负责「从库里读锚点、把结果翻成人能读的话」，这也守住了「Python 不碰库」。
     *
     * <p>⚠️ 它**不重建**：重建要花钱打模型，报告是免费的 —— 判定与重建分开，
     * 由 ADMIN 看着报告决定点哪些文章（`stalePostIds` 可以直接当 build 的 `postIds`）。
     */
    AiWikiStaleVO inspectStale();
}
