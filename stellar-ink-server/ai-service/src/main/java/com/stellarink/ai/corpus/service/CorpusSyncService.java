package com.stellarink.ai.corpus.service;

import com.stellarink.sharedmodel.vo.corpus.CorpusSyncResultVO;

/**
 * 把「已发布文章 + 已发布且公开的笔记」从 content-service 投影进 `ai_content_snapshot`。
 *
 * <p>为什么需要这层投影（而不是让 Python 直接读 `post`/`note`）：
 * 「什么算可见内容」的规则只能有一份，它在 content-service；
 * 而 Python 只允许读 `ai_*` 表（AGENTS §5 红线）。于是中间加一张**扁平、只读、AI 域自己的**
 * 投影表 —— Python 直读它，规则不必复制。
 *
 * <p>同步语义（三条，缺一条都会出问题）：
 * <ol>
 *   <li><b>每次都全量拉清单（只拉 id + 哈希，不含正文）</b>：增量拉取无法发现「上游删掉了什么」，
 *       而删除恰恰是最要紧的一条（下架 / 已删 / 笔记转私有）。语料是「一个博客的公开内容」，
 *       几十到几千行，全量清单远比「漏删」便宜。</li>
 *   <li><b>哈希没变就不动</b>：避免把没变的行也标成「刚同步」，从而让下游误判「这篇文章变了」。</li>
 *   <li><b>拉取失败时一行都不删</b>：宁可这一轮什么都不做（下一轮会补上），
 *       也不能因为一次网络抖动就把知识库清空。</li>
 * </ol>
 */
public interface CorpusSyncService {

    /**
     * 同步一次。
     *
     * @return 计数与失败原因（**不抛异常**：它是定时任务的一部分，
     *         抛出去只会变成一行无人看的定时任务错误日志，而调用方拿不到任何信息）
     */
    CorpusSyncResultVO sync();

    /** 投影表当前行数（面板/排查用，不必同步一次才知道）。 */
    int snapshotSize();
}
