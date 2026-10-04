package com.stellarink.content.corpus.service;

import com.stellarink.sharedmodel.enums.CorpusKind;
import com.stellarink.sharedmodel.vo.corpus.CorpusContentVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSliceVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 语料的**内部只读**服务：把「知识库应该包含哪些内容」这件事，收在内容服务里。
 *
 * <p>为什么由 content-service 提供而不是让 AI 侧直接读 `post` / `note`：
 * 「什么算可见内容」是这里的业务规则（文章 `status=1`；笔记 `status=1` 且 `visibility=PUBLIC`），
 * 而 Python 侧不读写 `user`/`post`（见 AGENTS §5 红线）。规则只能有一份 ——
 * 复制一份到 AI 侧，两份迟早分叉，而分叉的表现是「私有笔记被问答引用出来」。
 */
public interface InternalCorpusService {

    /**
     * 语料清单（不含正文），按 updatedAt 升序。
     *
     * @param since 只取该时间**之后**修改过的（游标续拉用；null = 全量）
     * @param ids   只取这些 id（null/空 = 不限）。注意文章与笔记的 id 各自自增，
     *              所以这里是「两种 kind 里 id 命中的都要」，需要精确定位时用 {@link #content}
     * @param limit 上限，会被夹到 [1, {@value #MAX_LIMIT}]
     */
    CorpusSliceVO slice(LocalDateTime since, List<Long> ids, int limit);

    /**
     * 单篇正文。**草稿、已删除、私有笔记一律 NOT_FOUND** —— 即使 id 正确。
     *
     * @throws com.stellarink.sharedmodel.exception.BusinessException 文档不存在或不可公开索引
     */
    CorpusContentVO content(CorpusKind kind, Long id);

    /** 单次请求的条目上限（够大以免对账频繁分页，够小以免一次搬空整库）。 */
    int MAX_LIMIT = 1000;

    /** 不传 limit 时的默认值。 */
    int DEFAULT_LIMIT = 500;
}
